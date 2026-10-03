#!/system/bin/sh
# ============================================================================
# OSPlus ADB 激活脚本
#
# 用法（与 Scene 的 up.sh 完全同一套方法，连调用形式都保持一致）：
#     adb shell sh /storage/emulated/0/Android/data/com.osplus.tools/up.sh
#
# 运行身份：shell（uid 2000）。**不需要 root。**
#
# 为什么脚本要放在「外部私有目录」而不是 assets 里直接跑：
#   应用私有目录 /data/data/<pkg> 对 shell 身份是不可读的（SELinux 域隔离），
#   adb 没法执行那里的文件。而 /storage/emulated/0/Android/data/<pkg> 的
#   属组是 ext_data_rw，shell 恰好在这个组里 —— 于是应用写得进去、
#   adb 又读得出来，这是 Android 上少见的「双方都能碰」的落点。
#   这正是 Scene 选择该路径的原因，照搬过来。
#
# 本脚本做三件事：
#   1. 把 OSPlus 从系统的后台管控里摘出来（这台机器实测会被 o-kill(40) 杀掉）；
#   2. 做一次 AOT 编译，减少冷启动耗时；
#   3. 把 shell 身份实际拥有的能力探测结果落盘，供应用读取展示。
# ============================================================================

PKG="com.osplus.tools"
OUTDIR="/data/local/tmp/osplus"
SRCDIR="/storage/emulated/0/Android/data/$PKG"

echo "============================================"
echo " OSPlus ADB 激活"
echo "============================================"
echo "包名  : $PKG"
echo "身份  : uid=$(id -u) ($(id -un 2>/dev/null || echo shell))"
echo "SELinux: $(getenforce 2>/dev/null || echo unknown)"
echo

# shell 身份下 /data/local/tmp 是可写的（属组 shell），
# 这是 Android 上唯一非 root 可执行的自有目录，Scene 也用它。
mkdir -p "$OUTDIR" 2>/dev/null

# ---------------------------------------------------------------------------
# 1. 后台存活：这台 ColorOS 会主动杀后台，逐项申请豁免
# ---------------------------------------------------------------------------
echo "[1/5] 后台存活配置"

# 电池优化白名单（deviceidle 豁免）
if dumpsys deviceidle whitelist +$PKG >/dev/null 2>&1; then
    echo "      电池优化白名单    OK"
else
    echo "      电池优化白名单    跳过（命令不可用）"
fi

# 允许后台运行（appops）
#
# 注意：Android 17 起 shell 身份已不再持有 MANAGE_APP_OPS_MODES，
# 这条命令实测抛 SecurityException，只有 Root 能执行。
# Scene 的 up.sh 里有一条同样的命令，但它用 >/dev/null 2>&1 把失败吞掉了，
# 于是在新系统上「看起来成功、实际什么都没做」——照搬时不要连这个盲点一起搬。
# 这里保留探测但如实输出，让用户知道该去系统设置里手动开。
if cmd appops set $PKG RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1; then
    echo "      后台运行权限      OK"
else
    echo "      后台运行权限      需 Root（Android 17 起 shell 无 MANAGE_APP_OPS_MODES）"
fi

# 注：`cmd deviceidle whitelist` 与上面的 `dumpsys deviceidle whitelist`
# 是同一功能的两个入口，留一个即可，这里不再重复调用。

# ---------------------------------------------------------------------------
# 2. AOT 编译：把解释执行的部分提前编译成机器码，冷启动更快
# ---------------------------------------------------------------------------
echo
echo "[2/5] AOT 编译（speed 模式）"
if cmd package compile -m speed -f $PKG >/dev/null 2>&1; then
    echo "      编译完成          OK"
else
    echo "      编译失败或已是最新  跳过"
fi

# ---------------------------------------------------------------------------
# 3. 能力探测：把「shell 身份到底能做什么」实测一遍并落盘
#
#    这些结论直接影响应用内的按钮是否可点 —— 与其在 Kotlin 里猜，
#    不如在这里实测一次，让应用读结果。探测的都是只读操作，无副作用。
# ---------------------------------------------------------------------------
echo
echo "[3/5] 探测 shell 身份能力"

CAP="$OUTDIR/capabilities.txt"
: > "$CAP"
echo "timestamp=$(date +%s 2>/dev/null)" >> "$CAP"
echo "uid=$(id -u)" >> "$CAP"

# CPU 频率节点：只读，shell 可以读（联发科读取修复依赖这条）
if [ -r /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq ]; then
    echo "read_cpu_freq=1" >> "$CAP"
else
    echo "read_cpu_freq=0" >> "$CAP"
fi

# CPU 调频节点：写测试。SELinux 标签 sysfs_devices_system_cpu 只给 root 写，
# 这里预期失败 —— 记录下来，应用据此把频率锁定按钮置灰。
if [ -w /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor ]; then
    echo "write_cpu_freq=1" >> "$CAP"
else
    echo "write_cpu_freq=0" >> "$CAP"
fi

# Magisk 模块目录：shell 读不到（root 专属域）
if [ -r /data/adb/modules ]; then
    echo "read_data_adb=1" >> "$CAP"
else
    echo "read_data_adb=0" >> "$CAP"
fi

# 进程管理能力
if command -v am >/dev/null 2>&1; then
    echo "manage_process=1" >> "$CAP"
else
    echo "manage_process=0" >> "$CAP"
fi

echo "      已写入 $CAP"

# ---------------------------------------------------------------------------
# 4. 落盘激活标记
# ---------------------------------------------------------------------------
echo
echo "[4/5] 写入激活标记"
echo "activated=$(date +%s 2>/dev/null)" > "$OUTDIR/activated"
echo "      OK"

# ---------------------------------------------------------------------------
# 5. 拉起常驻命令执行器（daemon）
#
# 这一步决定了「电脑 ADB」模式能不能真正用起来：
# 本脚本只在用户跑那一次时生效，之后应用要执行 shell 命令，
# 必须有一个长期存活的、以 shell 身份运行的进程来接活。
#
# Scene 的做法是部署一个编译好的 native daemon，再用 binder 通信，
# 性能好但要额外二进制。这里用纯 shell 循环 + 文件交换实现同样的效果：
#   * 应用把命令写到  $SRCDIR/.daemon/req
#   * daemon 取走执行，结果写回 .daemon/res，末尾追加 OSPLUS_EXIT=<码>
#   * daemon 每轮刷新 .daemon/beat，应用靠它判断守护进程是否还活着
#
# 代价是约 0.2 秒的轮询延迟；好处是不需要额外二进制、也不需要 root。
# 交换目录落在应用外部私有目录里 —— 只有本应用能写，其它应用碰不到，
# 因此不存在「任何 App 都能以 shell 身份执行命令」的提权口子。
# ---------------------------------------------------------------------------
echo
echo "[5/5] 拉起命令执行守护进程"

DAEMON_DIR="$SRCDIR/.daemon"
REQ="$DAEMON_DIR/req"
RES="$DAEMON_DIR/res"
BEAT="$DAEMON_DIR/beat"

mkdir -p "$DAEMON_DIR" 2>/dev/null

# 复用已有实例：重复执行 up.sh 不该拉起一堆并发循环，否则同一条命令会执行多次。
#
# 存活判定必须**同时**满足两个条件：
#   1) 记录的进程号还在（用 kill -0 探测）；
#   2) 心跳是新的。
# 只看心跳会误判 —— daemon 被杀后心跳文件会残留，时间戳仍然「很新」，
# 脚本据此认为它还活着而跳过启动，结果再也没人处理请求，
# 表现为「req 文件滞留、命令永远不返回」。实测踩过。
PIDF="$DAEMON_DIR/pid"
NOW=$(date +%s)
ALIVE=0
DPID=""
BEAT_AGE=0

if [ -f "$PIDF" ]; then
    DPID=$(tr -d '[:space:]' < "$PIDF" 2>/dev/null)
    case "$DPID" in
        ''|*[!0-9]*) DPID="" ;;
    esac
    if [ -n "$DPID" ] && kill -0 "$DPID" 2>/dev/null; then
        BEAT_TS=$(tr -d '[:space:]' < "$BEAT" 2>/dev/null)
        case "$BEAT_TS" in
            ''|*[!0-9]*) BEAT_TS=0 ;;
        esac
        BEAT_AGE=$((NOW - BEAT_TS))
        if [ "$BEAT_AGE" -lt 15 ]; then
            ALIVE=1
        fi
    fi
fi

# 进程还在但心跳已旧（卡死了）：先杀掉，下面重新拉起
if [ "$ALIVE" = "0" ] && [ -n "$DPID" ]; then
    kill -9 "$DPID" 2>/dev/null
fi

if [ "$ALIVE" = "1" ]; then
    echo "      已在运行（心跳 ${BEAT_AGE}s 前，PID $DPID），跳过启动"
else
    rm -f "$REQ" "$RES" 2>/dev/null

    # setsid 让循环脱离当前会话的控制终端，adb 断开后不会被一起带走
    setsid nohup /system/bin/sh -c '
        D="$1"; REQ="$2"; RES="$3"; BEAT="$4"
        # 先记下自己的 PID —— 下次执行本脚本时要靠它判断「上一个还在不在」
        echo $$ > "$D/pid" 2>/dev/null
        while true; do
            date +%s > "$BEAT" 2>/dev/null
            # 必须用 mv 原子取走，不能 cat 完再 rm：
            # 写端（应用）把命令写进文件需要时间，直接 cat 有机会读到只写了一半的
            # 内容甚至空文件，表现为「命令偶尔不执行、结果文件不出现」。
            # mv 在同分区内是重命名，要么看到完整文件要么看不到，不存在中间态。
            if [ -f "$REQ" ]; then
                if mv "$REQ" "$REQ.work" 2>/dev/null; then
                    cmd=$(cat "$REQ.work" 2>/dev/null)
                    rm -f "$REQ.work" 2>/dev/null
                    if [ -n "$cmd" ]; then
                        sh -c "$cmd" > "$RES" 2>&1
                        echo "OSPLUS_EXIT=$?" >> "$RES"
                    fi
                fi
            fi
            sleep 0.1
        done
    ' sh "$DAEMON_DIR" "$REQ" "$RES" "$BEAT" >/dev/null 2>&1 &

    sleep 1
    if [ -f "$BEAT" ]; then
        echo "      守护进程已启动（PID 见 pgrep）"
    else
        echo "      启动失败：未检测到心跳文件"
    fi
fi

echo
echo "============================================"
echo " 激活完成"
echo "============================================"
echo "应用内可在「设置 → ADB 模式」查看探测结果。"
echo
echo "提示：本脚本只授予 shell 身份能力，与 Root 仍有区别 ——"
echo "      /data/adb 下的模块文件、CPU 调频节点写入依然需要 Root。"
echo
