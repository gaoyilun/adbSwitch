#!/bin/bash
# 直接用 NDK 的 clang++ 编译 Zygisk 模块（不需要 Gradle / CMake），然后打包成 Magisk 模块 zip。
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-/Users/ellen/Library/Android/sdk/ndk/27.3.13750724}}"
PREBUILT="$NDK/toolchains/llvm/prebuilt/darwin-x86_64"
CLANG="$PREBUILT/bin/clang++"

if [ ! -x "$CLANG" ]; then
    echo "找不到 NDK clang++: $CLANG" >&2
    echo "请先安装 NDK 并设置 ANDROID_NDK_HOME，或修改脚本里的 NDK 变量" >&2
    exit 1
fi

API=26
OUT="$HERE/module/zygisk"
rm -rf "$OUT"
mkdir -p "$OUT"

build_abi() {
    local abi="$1" target="$2" extra="$3"
    echo "==> 编译 $abi"
    "$CLANG" \
        --target="${target}${API}" \
        --sysroot="$PREBUILT/sysroot" \
        $extra \
        -std=c++17 -fPIC -shared -O2 -fno-rtti \
        -fvisibility=hidden -fvisibility-inlines-hidden \
        -static-libstdc++ \
        -Wall -Wextra -Wno-unused-parameter \
        -I"$HERE/jni" \
        -o "$OUT/$abi.so" \
        "$HERE/jni/main.cpp" \
        -llog
}

build_abi arm64-v8a   aarch64-linux-android    ""
build_abi armeabi-v7a armv7-linux-androideabi  "-march=armv7-a"
build_abi x86         i686-linux-android       ""
build_abi x86_64      x86_64-linux-android     ""

# 体积优化
for f in "$OUT"/*.so; do
    "$PREBUILT/bin/llvm-strip" --strip-all "$f" 2>/dev/null || true
done

# 自检：产物必须比源码新，且导出符号必须存在
MAIN="$HERE/jni/main.cpp"
for f in "$OUT"/*.so; do
    if [ "$f" -ot "$MAIN" ]; then
        echo "产物自检失败：$f 比源码旧" >&2
        exit 1
    fi
    if ! "$PREBUILT/bin/llvm-nm" -D --defined-only "$f" 2>/dev/null | grep -q "zygisk_module_entry"; then
        echo "产物自检失败：$f 未导出 zygisk_module_entry" >&2
        exit 1
    fi
done
echo "==> 自检通过：4 个 ABI 均已导出 zygisk_module_entry"

# 打包
VERSION="$(grep '^version=' "$HERE/module/module.prop" | cut -d= -f2)"
ZIP="$HERE/../dist/DevOptsHider-Zygisk-v${VERSION}.zip"
mkdir -p "$(dirname "$ZIP")"
rm -f "$ZIP"
(cd "$HERE/module" && zip -q -r -9 "$ZIP" . -x '*.DS_Store' -x '__MACOSX*')

echo "==> 已生成: $ZIP"
ls -la "$ZIP"
