#!/bin/bash
# 一键构建：仅构建当前主方案「调试开关 (AdbSwitch)」APK。
# 产物用 Gradle 默认输出目录与统一命名（包名-版本名-架构-构建类型）：
#   adbswitch/app/build/outputs/apk/release/com.elleng.adbswitch-<版本>-universal-release.apk
# 旧的 Xposed / Zygisk / 探针 方案已归档到 archive/，不再从这里构建。
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/jdk-21.0.12.jdk/Contents/Home}"

# 找一个可用的 gradle：优先本机缓存的发行版
GRADLE=""
if command -v gradle >/dev/null 2>&1; then
    GRADLE="gradle"
else
    for d in "$HOME"/.gradle/wrapper/dists/gradle-8.13-bin/*/gradle-8.13; do
        [ -x "$d/bin/gradle" ] && GRADLE="$d/bin/gradle" && break
    done
fi
if [ -z "$GRADLE" ]; then
    echo "找不到 Gradle 8.13（本机缓存或 PATH 里都没有）" >&2
    exit 1
fi

# 签名 keystore / 配置缺失时直接报错退出，绝不静默生成随机密钥：
# 随机密钥意味着「另一台机器 / 全新 clone」构建的 APK 签名与此前不同，
# 用户覆盖安装即 INSTALL_FAILED_UPDATE_INCOMPATIBLE（历史踩坑，见 README）。
# 维护者机器上 keystore/ 已存在；全新 clone 请按下方提示用固定口令自建并妥善保存。
if [ ! -f "$HERE/keystore/adbswitch.jks" ] || [ ! -f "$HERE/keystore/signing.properties" ]; then
    echo "错误：缺少 keystore/adbswitch.jks 或 keystore/signing.properties，release 构建中止。" >&2
    echo "release 必须使用固定密钥，否则无法覆盖升级。请用固定口令生成后重试：" >&2
    echo "  keytool -genkeypair -keystore keystore/adbswitch.jks -keyalg RSA -keysize 2048 \\" >&2
    echo "    -validity 10950 -alias adbswitch -storepass '<固定口令>' -keypass '<固定口令>' \\" >&2
    echo "    -dname 'CN=AdbSwitch, OU=Dev, O=AdbSwitch, C=CN'" >&2
    echo "  并把口令/别名写进 keystore/signing.properties（storePassword/keyAlias/keyPassword）。" >&2
    exit 1
fi

echo "==> 构建调试开关 (AdbSwitch) APK"
(cd "$HERE/adbswitch" && "$GRADLE" :app:assembleRelease \
    --no-daemon --console=plain)

echo "==> 产物（Gradle 默认输出，命名=包名-版本名-架构-构建类型）"
ls -la "$HERE"/adbswitch/app/build/outputs/apk/release/*.apk
