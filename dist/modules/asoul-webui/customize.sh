CFG_PATH="/data/adb/naki/asopt.conf"
MODS_PATH="/data/adb/modules"

mkdir -p /data/adb/naki $MODS_PATH
mode=`grep mode= $CFG_PATH`
rt=`grep rt= $CFG_PATH`
games=`grep -E '^[^#][^ ]+ [0-9]+ [0-9]+$' $CFG_PATH`

[ -z $mode ] && mode=`grep mode= /sdcard/Android/naki/asopt/asopt.conf`
[ -z $mode ] && mode="mode=0"
[ -z $rt ] && rt="rt=0"
[ -z $games ] && games="com.miHoYo.Yuanshen 0 0"

echo "
- 配置文件位于 $CFG_PATH
- The config file is at $CFG_PATH
"
echo "# mode：运行模式 / Operation Mode
# 0：硬亲和，理论上表现更好 / Affinity, performs better in theory
# 1：软迁移，帧率可能更稳定 / Soft migrate, fps maybe more stable
# 2：硬迁移，帧率可能更稳定 / Hard migrate, fps maybe more stable

# rt：实时模式 / Real-Time Mode
# 0：调度器默认行为 / Scheduler default behavior
# 1：可能更流畅，但可能导致卡死 / maybe smoother, but may cause freeze

# 可对游戏单独指定 mode 和 rt / Per-game override of mode and rt
# 格式 / Format：包名(package name) mode rt
# 例 / Example：com.miHoYo.Yuanshen 0 0
# 一行一个，未匹配的游戏使用上面的全局值
# One per line, global values above as fallback

# ***保存后即时应用，切换游戏后生效***
# ***Applied on save, effective on next app switch***

$mode
$rt
$games" > $CFG_PATH

chmod +x $MODPATH/AsoulOpt
rm -rf $MODPATH/customize.sh /sdcard/Android/naki/asopt

killall -15 AsoulOpt
cp -af $MODPATH $MODS_PATH
sh $MODS_PATH/asoul_affinity_opt/service.sh
