#!/usr/bin/env python3
"""审计 release APK 里资源是否被资源压缩裁掉。

为什么需要这个脚本（本工程踩过的坑）：
    开了 isShrinkResources 后，AAPT2 只保留「能证明被用到」的资源。
    而 R8 会把 R.string.xxx / R.layout.xxx 内联成资源 ID 常量，
    一旦引用这些资源的调用点本身被判定不可达（比如某个功能被常量开关关掉、
    R8 把整条调用链当死代码删了），资源引用就随之消失，
    资源压缩器再把这些资源从 resources.arsc 里裁掉。
    后果是「代码在、资源没了」：功能没启用时看不出问题，
    等哪天把开关打开就 Resources.NotFoundException 崩溃。

用法：
    python3 tools/audit_apk_resources.py
    （默认审计 app/build/outputs/apk/release/app-release.apk，可传路径参数）

输出：缺失资源列表；退出码 0 = 全部在包里，1 = 有缺失。
"""
import os
import re
import sys
import zipfile

SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res")
APK = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..",
    "app", "build", "outputs", "apk", "release", "app-release.apk")
# 这些类型是文件型资源，资源名 = 文件去掉扩展名
FILE_KINDS = ("drawable", "layout", "mipmap", "xml", "raw")
# values 里按 <tag name="..."> 声明的声明式资源
VALUE_TAGS = ("string", "color", "style", "dimen", "bool", "integer")


def declared_names():
    """产出 (类别, 资源名) 列表。"""
    for root, _, files in os.walk(SRC):
        kind = os.path.basename(root).split("-")[0]
        if kind in FILE_KINDS:
            for f in files:
                if f.endswith(".xml"):
                    yield f"{kind}/{f}", os.path.splitext(f)[0]
    values_dir = os.path.join(SRC, "values")
    for root, _, files in os.walk(values_dir):
        for f in files:
            if not f.endswith(".xml"):
                continue
            with open(os.path.join(root, f), encoding="utf-8") as fh:
                text = fh.read()
            for m in re.finditer(r"<(%s)\s+name=\"([^\"]+)\"" % "|".join(VALUE_TAGS), text):
                yield f"{m.group(1)}/{m.group(2)}", m.group(2)


def main():
    if not os.path.exists(APK):
        print("APK 不存在：%s" % APK)
        return 1
    arsc = zipfile.ZipFile(APK).read("resources.arsc")

    def in_arsc(name):
        # arsc 字符串池逐条标记编码，utf-8/utf-16 两种编码都要数，否则会假阴性
        return any(name.encode(enc) in arsc
                   for enc in ("utf-8", "utf-16-le", "utf-16-be"))

    missing, total = [], 0
    for label, name in declared_names():
        total += 1
        if not in_arsc(name):
            missing.append(label)
    print("检查 %d 个资源，缺失 %d 个" % (total, len(missing)))
    for m in missing:
        print("  缺失:", m)
    if missing:
        print("\n处理方式二选一：")
        print("  1) 若它其实已无人引用 → 删掉源码里的声明；")
        print("  2) 若代码里还会用到（功能暂未启用）→ 用 -keep/-keepclassmembers "
              "保住对应类的引用（本工程见 proguard-rules.properties 的 update 段）。")
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main())
