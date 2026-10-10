#!/bin/bash
# 上传 APK 到蒲公英（bootstrap 与发版都走这个）。
# 用法：./upload-pgyer.sh <apk-path>
# 蒲公英密钥从 adbswitch/pgyer.properties 读取（gitignored，构建时注入 BuildConfig 的同款来源）。
set -euo pipefail

APK="${1:?用法: $0 <apk-path>}"
HERE="$(cd "$(dirname "$0")" && pwd)"
PROPS="$HERE/adbswitch/pgyer.properties"
if [ ! -f "$PROPS" ]; then
  echo "找不到 $PROPS（从 adbswitch/pgyer.properties.example 复制并填值）" >&2
  exit 1
fi
PGYER_API_KEY="$(grep '^PGYER_API_KEY=' "$PROPS" | cut -d= -f2-)"
PGYER_APP_KEY="$(grep '^PGYER_APP_KEY=' "$PROPS"  | cut -d= -f2-)"
if [ -z "$PGYER_API_KEY" ] || [ -z "$PGYER_APP_KEY" ]; then
  echo "pgyer.properties 缺少 PGYER_API_KEY / PGYER_APP_KEY" >&2
  exit 1
fi

echo "==> 上传 $APK 到蒲公英"
curl -# -F "_api_key=$PGYER_API_KEY" \
     -F "appKey=$PGYER_APP_KEY" \
     -F "file=@$APK" \
     https://www.pgyer.com/apiv2/app/upload
echo
echo "==> 上传完成。到蒲公英后台确认版本；安装页短链回填 UpdateConfig.PGYER_PAGE_URL"
