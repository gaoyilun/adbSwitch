#!/system/bin/sh
# Magisk App 里点「操作」按钮时执行：查看/切换模式、追加目标包名
# 用法：
#   直接点                    -> 打印当前配置
#   action.sh mode whitelist|blacklist
#   action.sh add <package>
#   action.sh del <package>
#   action.sh debug on|off

DIR=/data/adb/devhider
mkdir -p "$DIR"

case "$1" in
  mode)
    echo "$2" > "$DIR/mode.txt"
    echo "mode -> $(cat "$DIR/mode.txt")"
    ;;
  add)
    grep -qx "$2" "$DIR/packages.txt" 2>/dev/null || echo "$2" >> "$DIR/packages.txt"
    echo "added $2"
    ;;
  del)
    grep -vx "$2" "$DIR/packages.txt" 2>/dev/null > "$DIR/packages.txt.tmp"
    mv "$DIR/packages.txt.tmp" "$DIR/packages.txt"
    echo "removed $2"
    ;;
  debug)
    if [ "$2" = "on" ]; then
      touch "$DIR/debug"
      echo "debug on: adb logcat -s DevHider"
    else
      rm -f "$DIR/debug"
      echo "debug off"
    fi
    ;;
  *)
    echo "=== DevOpts Hider (Zygisk) ==="
    echo "mode : $(cat "$DIR/mode.txt" 2>/dev/null)"
    echo "--- packages.txt ---"
    cat "$DIR/packages.txt" 2>/dev/null
    echo "--- exclude.txt ---"
    cat "$DIR/exclude.txt" 2>/dev/null
    echo "提示：改动配置后需要重启目标 App（或重启手机）才生效"
    ;;
esac

chmod 0644 "$DIR"/*.txt 2>/dev/null
