#!/bin/bash
# 生成 GitHub 通道用的 update/update.json（含 sha256，供完整链路测试：下载→sha256 校验→安装器）。
# 用法：./make-update-json.sh <apk-path> <versionCode> <versionName> [notes]
# 例：  ./make-update-json.sh app-release.apk 2 1.0.1 "修复..."
# 产物：update/update.json，并打印 GITHUB_UPDATE_JSON_URL / GITHUB_RELEASES_URL 供回填 UpdateConfig。
set -euo pipefail

APK="${1:?用法: $0 <apk-path> <versionCode> <versionName> [notes]}"
VC="${2:?versionCode}"
VN="${3:?versionName}"
NOTES="${4:-}"

SHA="$(shasum -a 256 "$APK" | awk '{print $1}')"
APK_NAME="$(basename "$APK")"
TAG="v$VN"
OWNER_REPO="gaoyilun/adbSwitch"   # 若仓库路径不同请改这一行
APK_URL="https://github.com/$OWNER_REPO/releases/download/$TAG/$APK_NAME"
JSON_URL="https://raw.githubusercontent.com/$OWNER_REPO/main/update/update.json"
REL_URL="https://github.com/$OWNER_REPO/releases"

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/update/update.json"
mkdir -p "$(dirname "$OUT")"

cat > "$OUT" <<EOF
{
  "versionCode": $VC,
  "versionName": "$VN",
  "notes": "$NOTES",
  "mandatory": false,
  "apkUrl": "$APK_URL",
  "fallbackUrl": "$REL_URL",
  "sha256": "$SHA"
}
EOF

echo "==> 已写入 $OUT"
echo "sha256               = $SHA"
echo "GITHUB_UPDATE_JSON_URL = $JSON_URL"
echo "GITHUB_RELEASES_URL    = $REL_URL"
echo
echo "下一步："
echo "  1) git add update/update.json && git commit && git push 到 main（raw 直链才会生效）"
echo "  2) 在 GitHub 创建 Release ${TAG}，上传同名 APK（${APK_NAME}）"
echo "  3) 把上面两个 URL 回填进 UpdateConfig.GITHUB_UPDATE_JSON_URL / GITHUB_RELEASES_URL，重新构建"
echo "  4) 跑 ./upload-pgyer.sh 把同款 APK 也传到蒲公英（双通道并存：GitHub 优先、失败查蒲公英）"
