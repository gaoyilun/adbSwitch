#!/system/bin/sh
# 开机早期：建好配置目录，保证 Zygisk 模块在 preAppSpecialize 时读得到名单
mkdir -p /data/adb/devhider
chmod 0755 /data/adb/devhider

[ -f /data/adb/devhider/packages.txt ] || printf '# 一行一个包名，命中这些包才生效（仅在 whitelist 模式下需要）\n' > /data/adb/devhider/packages.txt
[ -f /data/adb/devhider/exclude.txt ]  || printf '# 一行一个包名，这些包永远不处理\n' > /data/adb/devhider/exclude.txt
[ -f /data/adb/devhider/mode.txt ]     || printf 'blacklist\n' > /data/adb/devhider/mode.txt

chmod 0644 /data/adb/devhider/packages.txt /data/adb/devhider/exclude.txt /data/adb/devhider/mode.txt
