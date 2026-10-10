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

# 没有 keystore / 签名配置就现生成：密钥与口令只落 keystore/（gitignored，仓库公开也不泄），
# 口令随机生成写进 signing.properties，任何脚本/gradle 里都不留明文。
if [ ! -f "$HERE/keystore/adbswitch.jks" ] || [ ! -f "$HERE/keystore/signing.properties" ]; then
    echo "==> 生成签名 keystore 与 signing.properties"
    mkdir -p "$HERE/keystore"
    PASS="$(openssl rand -hex 16)"
    keytool -genkeypair -v -keystore "$HERE/keystore/adbswitch.jks" \
        -keyalg RSA -keysize 2048 -validity 10950 -alias adbswitch \
        -storepass "$PASS" -keypass "$PASS" \
        -dname "CN=AdbSwitch, OU=Dev, O=AdbSwitch, L=HK, ST=HK, C=CN" >/dev/null
    (umask 077 && cat > "$HERE/keystore/signing.properties" <<EOF
storePassword=$PASS
keyAlias=adbswitch
keyPassword=$PASS
EOF
    )
fi

echo "==> 构建调试开关 (AdbSwitch) APK"
(cd "$HERE/adbswitch" && "$GRADLE" :app:assembleRelease \
    --no-daemon --console=plain)

echo "==> 产物（Gradle 默认输出，命名=包名-版本名-架构-构建类型）"
ls -la "$HERE"/adbswitch/app/build/outputs/apk/release/*.apk
