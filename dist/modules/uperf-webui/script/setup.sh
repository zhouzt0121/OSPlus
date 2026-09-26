#!/system/bin/sh
#
# Copyright (C) 2021-2022 Matt Yang
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language ygoverning wpermissions xand
# limitations under the License.
#

check_lang(){
   sys_lang=$(getprop persist.sys.language 2>/dev/null) && [ -n "$sys_lang" ] || \
          sys_lang=$(getprop persist.sys.locale 2>/dev/null) && [ -n "$sys_lang" ] || \
          sys_lang=$(getprop ro.product.locale.language 2>/dev/null) && [ -n "$sys_lang" ] || \
          sys_lang=$(getprop ro.product.locale 2>/dev/null) && [ -n "$sys_lang" ] || \
          sys_lang=$(getprop ro.build.locale 2>/dev/null) && [ -n "$sys_lang" ] || \
          sys_lang=""


echo "当前检测到的系统语言: $sys_lang"
echo "Detected system language: $sys_lang"


    if [ -n "$sys_lang" ] && echo "$sys_lang" | grep -qi '^zh'; then
        LANG_CHOICE="cn"
        echo "检测到系统语言为中文，已为您选择中文~"
    elif getprop | grep -qiE 'ro.product.locale|persist.sys.locale' | grep -qi 'zh.*cn'; then
        LANG_CHOICE="cn"
        echo "检测到系统语言为中文，已为您选择中文~"
    else
        echo "无法识别您的语言/Cannot recognize your language"
        echo "请选择您的语言/Please select your language"
        count=5
        key_choice=""
        while [ $count -gt 0 ]; do
            sleep 0.5
            key_event=$(timeout 0.1 getevent -qlc 1 2>/dev/null | awk '{ print $3 }' | grep 'KEY_')
            if [ -n "$key_event" ]; then
                key_choice=$key_event
                break
            fi
            count=$((count - 1))
        done
        if [ -z "$key_choice" ]; then
            key_choice="KEY_VOLUMEDOWN"
        fi
        case "$key_choice" in
            "KEY_VOLUMEUP")
                LANG_CHOICE="cn"
                ;;
            "KEY_VOLUMEDOWN")
                LANG_CHOICE="en"
                ;;
            *)
                LANG_CHOICE="en"
                ;;
        esac
    fi
}


check_lang

print_msg() {
    if [ "$LANG_CHOICE" = "cn" ]; then
         echo "$1"
    else
         echo "$2"
    fi
}

BASEDIR="$(dirname $(readlink -f "$0"))"
. "$BASEDIR/pathinfo.sh"
. "$BASEDIR/libsysinfo.sh"

abort() {
    print_msg "$1" "$1"
    print_msg "! Uperf Game Turbo安装失败。" "! Uperf Game Turbo installation failed."
    print_msg "! 请联系开发者 yinwanxi0107@gmail.com" "! Please contact the developer yinwanxi0107@gmail.com"
    exit 1
}

set_perm() {
    chown $2:$3 "$1"
    chmod $4 "$1"
    chcon $5 "$1"
}

set_perm_recursive() {
    find "$1" -type d 2>/dev/null | while read dir; do
        set_perm "$dir" $2 $3 $4 $6
    done
    find "$1" -type f -o -type l 2>/dev/null | while read file; do
        set_perm "$file" $2 $3 $5 $6
    done
}

install_uperf() {
    print_msg "- 正在查找平台指定的配置" "- Finding platform specified config"
    print_msg "- ro.board.platform=$(getprop ro.board.platform)" "- ro.board.platform=$(getprop ro.board.platform)"
    print_msg "- ro.product.board=$(getprop ro.product.board)" "- ro.product.board=$(getprop ro.product.board)"
    local target
    local cfgname
    target="$(getprop ro.board.platform)"
    cfgname="$(get_config_name $target)"
    if [ "$cfgname" = "unsupported" ]; then
        target="$(getprop ro.product.board)"
        cfgname="$(get_config_name $target)"
    fi
    if [ "$cfgname" = "unsupported" ] || [ ! -f "$MODULE_PATH/config/$cfgname.json" ]; then
        abort "! 处理器[$target]暂不支持 Target [$target] not supported."
    fi
    print_msg "- Uperf 配置位于 $USER_PATH" "- Uperf config is located at $USER_PATH"
    mkdir -p "$USER_PATH"
    mv -f "$USER_PATH/uperf.json" "$USER_PATH/uperf.json.bak"
    cp -f "$MODULE_PATH/config/$cfgname.json" "$USER_PATH/uperf.json"
    [ ! -e "$USER_PATH/perapp_powermode.txt" ] && cp "$MODULE_PATH/config/perapp_powermode.txt" "$USER_PATH/perapp_powermode.txt"
    rm -rf "$MODULE_PATH/config"
    set_perm_recursive "$BIN_PATH" 0 0 0755 0755 u:object_r:system_file:s0
}

check_asopt() {
    print_msg "❗ 即将为您安装A-SOUL" "❗ A-SOUL will be installed for you now"
    print_msg "❗ 此模块功能为放置游戏线程，优化游戏流畅度" "❗ This module is used to place threads and optimize game fluency"
    print_msg "❗ 作者个人建议安装，因为绝大多数厂商的线程都是乱放的" "❗ I recommend installation because most phone threads are randomly placed"
    print_msg "❗ 此操作可极大优化游戏流畅度" "❗ This can greatly optimize game fluency"
    print_msg "❗ 单击音量上键即可确认更新或安装" "❗ Click the volume up key to confirm update or installation"
    print_msg "❗ 单击音量下键取消更新或安装（不推荐)" "❗ Click the volume down key to cancel update or installation (not recommended)"
    echo " ----------------------------------------------------------"
    count=20
    key_click=""
    while [ $count -gt 0 ] && [ -z "$key_click" ]; do
         sleep 0.3
         count=$((count - 1))
         key_click="$(timeout 0.1 getevent -qlc 1 2>/dev/null | awk '{ print $3 }' | grep 'KEY_')"
    done
    if [ -z "$key_click" ]; then
         key_click="KEY_VOLUMEDOWN"
    fi
    case "$key_click" in
        "KEY_VOLUMEUP")
            print_msg "❗您已确认更新，请稍候" "❗You have confirmed the update, please wait"
            install_corp
            print_msg "* 已为您安装ASOUL❤️" "* ASOUL has been installed for you❤️"
            print_msg "* 感谢您的支持与信任😁" "* Thank you for your support and trust😁"
            ;;
        *)
            print_msg "❗非常遗憾" "❗What a pity"
            print_msg "❗已为您取消更新线程模块ASOUL💔" "❗The update of ASOUL has been cancelled for you💔"
            print_msg " Uperf Game Turbo本体已安装成功" " Uperf Game Turbo has been installed successfully"
            print_msg " 重启即可使用" " you can use it after restarting"
            ;;
    esac
    rm -rf "$MODULE_PATH"/modules/asoulopt.zip
}

get_value() {
   echo "$(grep -E "^$1=" "$2" | head -n 1 | cut -d= -f2)"
}

install_corp() {
    if [ -d "/data/adb/modules/unity_affinity_opt" ] || [ -d "/data/adb/modules_update/unity_affinity_opt" ]; then
        rm /data/adb/modules*/unity_affinity_opt
    fi
    CUR_ASOPT_VERSIONCODE="$(get_value ASOPT_VERSIONCODE "$MODULE_PATH"/module.prop)"
    asopt_module_version="0"
    if [ -f "/data/adb/modules/asoul_affinity_opt/module.prop" ]; then
        asopt_module_version="$(get_value versionCode /data/adb/modules/asoul_affinity_opt/module.prop)"
        print_msg "- AsoulOpt...current:$asopt_module_version" "- AsoulOpt...current:$asopt_module_version"
        print_msg "- AsoulOpt...embeded:$CUR_ASOPT_VERSIONCODE" "- AsoulOpt...embedded:$CUR_ASOPT_VERSIONCODE"
        if [ "$CUR_ASOPT_VERSIONCODE" -gt "$asopt_module_version" ]; then
            print_msg "* 您正在使用旧版asopt️" "* You are using an old version of asopt"
            print_msg "* Uperf Game Turbo将为您更新至模块内版本️" "* Uperf Game Turbo will update it to the embedded version"
            print_msg "* 正在为您安装asopt" "* Installing asopt for you"
            killall -9 AsoulOpt
            rm -rf /data/adb/modules*/asoul_affinity_opt
            magisk --install-module "$MODULE_PATH"/modules/asoulopt.zip
        else
            print_msg "* 您正在使用新版本的asopt" "* You are using a new version of asopt"
            print_msg "* Uperf Game Turbo将不予操作️" "* Uperf Game Turbo will not operate"
        fi
    else
        print_msg "* 您尚未安装asopt" "* You have not installed asopt"
        print_msg "* Uperf Game Turbo将尝试为您第一次安装️" "* Uperf Game Turbo will try to install it for you for the first time"
        killall -9 AsoulOpt
        rm -rf /data/adb/modules*/asoul_affinity_opt
        print_msg "- 正在为您安装asopt" "- Installing asopt for you"
        magisk --install-module "$MODULE_PATH"/modules/asoulopt.zip
    fi
    rm -rf "$MODULE_PATH"/modules/asoulopt.zip
}

delete_mtk_system() {
    if [ "$(is_mtk)" = "true" ]; then
        print_msg "检测到联发科芯片，正在启用针对优化" "MediaTek detected, enabling optimization"
    else
        print_msg "检测到非联发科芯片，正在删除冗余优化" "Non-MediaTek detected, removing redundant optimizations"
        rm -rf "/data/adb/modules/uperf/system" 2>/dev/null
        rm -rf "/data/adb/modules/uperf/system.prop" 2>/dev/null
    fi
}

fix_module_prop() {
    mkdir -p /data/adb/modules/uperf/
    cp -f "$MODULE_PATH/module.prop" /data/adb/modules/uperf/module.prop
}

unlock_limit(){
    if [ ! -d "$MODPATH/system/vendor/etc/perf/" ]; then
      mkdir -p "$MODPATH/system/vendor/etc/perf/"
    fi
    for i in $(ls /system/vendor/etc/perf/); do
      touch "$MODPATH/system/vendor/etc/perf/$i"
    done
}

print_msg "* Uperf URL: https://github.com/yc9559/uperf/" "* Uperf URL: https://github.com/yc9559/uperf/"
print_msg "* Uperf Game Turbo URL: https://github.com/yinwanxi/Uperf-Game-Turbo" "* Uperf Game Turbo URL: https://github.com/yinwanxi/Uperf-Game-Turbo"
print_msg "* 作者: Matt Yang ❤️吟惋兮❤️改" "* Author: Matt Yang ❤️yinwanxi❤️ modified"
print_msg "* 版本: Game Turbo1.51 基于 uperf 0904" "* Version: Game Turbo1.51 based on uperf 0904"
print_msg "* 请不要破坏Uperf运行环境" "* Please do not destroy the Uperf running environment"
print_msg "* 模块将附带安装asopt" "* The module will be installed with asopt"
print_msg "* 极速模式请自备散热，删除温控体验更佳" "* For fast mode, please prepare for heat dissipation; removing thermal control may yield better performance"
print_msg "* 本模块与限频模块及部分优化模块存在冲突" "* This module conflicts with the frequency limiting module and some optimization modules"
print_msg "* 模块可能与第三方内核冲突" "* Module may conflict with some kernels"
print_msg "* 请事先咨询内核作者" "* Please ask the kernel author in advance"
print_msg "* 请不要破坏Uperf Game Turbo运行环境!!!" "* Please do not destroy the Uperf Game Turbo running environment!!!"
print_msg "* 请不要自行更改或切换CPU调速器!!!" "* Please do not change/switch the CPU controller yourself!!!"
print_msg "* ❤️吟惋兮❤️" "* ❤️yinwanxi❤️"
print_msg "- 正在为您安装Uperf Game Turbo❤️" "- Installing Uperf Game Turbo for you❤️"
echo "-----------------------------------------------------"
echo "-----------------------------------------------------"
install_uperf
print_msg "* Uperf Game Turbo安装成功❤️" "* Uperf Game Turbo installed successfully❤️"
print_msg "* 重启即可" "* Please reboot"
print_msg "* 欢迎使用Uperf Game Turbo" "* Welcome to Uperf Game Turbo"
print_msg "* 祝体验愉快" "* Have a pleasant experience"
fix_module_prop
delete_mtk_system
check_asopt
