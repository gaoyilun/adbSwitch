#!/bin/bash
# 一键构建：仅构建当前主方案「USB 调试快捷开关」APK，产物放到 dist/
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

# 没有 keystore 就现生成一个
if [ ! -f "$HERE/keystore/devhider.jks" ]; then
    echo "==> 生成签名 keystore"
    mkdir -p "$HERE/keystore"
    keytool -genkeypair -v -keystore "$HERE/keystore/devhider.jks" \
        -keyalg RSA -keysize 2048 -validity 10950 -alias devhider \
        -storepass devhider123 -keypass devhider123 \
        -dname "CN=DevOptsHider, OU=Dev, O=DevOptsHider, L=HK, ST=HK, C=CN" >/dev/null
fi

echo "==> 构建 USB 调试快捷开关 APK"
(cd "$HERE/usbswitch" && "$GRADLE" :app:assembleRelease \
    --no-daemon --console=plain)

echo "==> 汇总产物"
mkdir -p "$HERE/dist"
cp "$HERE/usbswitch/app/build/outputs/apk/release/app-release.apk" \
   "$HERE/dist/DevOptsHider-UsbSwitch-v1.0.0.apk"

ls -la "$HERE/dist"
