MODDIR=${0%/*}
lock_val() {
    chown 0:0 $2
    chmod +w  $2
    echo "$1" | tee $2 > /dev/null 2>&1
    chmod a-w $2
}

lock_val "9" "/sys/devices/system/cpu/cpu*/core_ctl/m??_cpus"
lock_val "0" "/sys/module/migt/parameters/*cluster"

until [ -d /data/data/android ]; do sleep 1; done
killall -15 AsoulOpt; nohup $MODDIR/AsoulOpt > /dev/null 2>&1 &
