# OSPlus · Android 性能监视与调优工具

包名 `com.osplus.tools` ｜ 版本 **2.8.1**（versionCode 26）｜ minSdk 33 (Android 13) ｜ targetSdk 36 ｜ compileSdk 37

完全自研的**本地**性能监视与调优工具。数据全部来自 `/proc`、`/sys` 与系统 API，
**不做任何云端上报**；涉及内核写入的功能全部经 Root 执行。

---

## 一、有什么功能

### 页面总览

**统一顶栏**（左侧标题 + 右侧页面动作）+ 底部悬浮导航分
**概览 / 性能 / 帧率 / 电源** 四个一级页；**设置**挂在顶栏右侧。
概览与性能页的卡片可下钻到 **内存 / GPU / CPU / 进程 / 性能调度** 五个二级页，
设置页另有 **系统开关 / 提权管理 / 预测性返回** 三个三级页。

二级页是盖在一级页之上的**全屏浮层**，支持 **Android 预测性返回**：
从屏幕边缘右滑时，浮层跟手右移、缩角并轻微缩小，下面的一级页实时露出；
手势拖回原位即取消，越过阈值才真正返回。
跟手幅度（位移 / 缩放 / 圆角 / 回弹耗时 / 压暗）在
**设置 → 预测性返回** 里可实时调整，无需重启。

| 一级页 | 一句话 |
|---|---|
| **概览** | 健康结论条 + 2×2 指标网格（CPU / GPU / 内存圆环 + 帧率折线）+ 设备概况 |
| **性能** | 进程摘要 + 5 秒~30 分可选时间窗的六条趋势 + 每核柱状图 + 四个调优入口 |
| **帧率** | 实时 FPS / 帧耗时 / 卡顿计数 + 多档分析窗口 + 录制留档 + 历史回看 + CSV 导出 |
| **电源** | 电池态势 + **耗电统计录制** + 充电统计 |

### 各页能力

| 页面 | 能做什么 | 数据来源 |
|---|---|---|
| **概览** | 健康结论条按权限 / SoC 结温 / 电池温度 / 内存四项判据给出「算不算正常」；CPU / GPU / 内存用**圆环**表达水位（名称、百分比、说明行全收在环心）；第四格是**帧率折线**——一个瞬时百分比会把「稳定 60 帧」和「在 60 与 30 之间来回跳」画成同一个数字。四个动作（清理内存 / 清理交换 / 记录帧率 / 设置）收在顶栏右侧，滚到哪都够得着 | `/proc`、`/sys` |
| **性能** | 进程摘要置顶（点进进程管理）；**5 秒 / 1 分 / 5 分 / 30 分**时间窗分段控件；CPU 总占用、内存、GPU 频率、整机功耗、实时帧率、电池温度六条趋势；每核占用与频率柱状图；调优入口（CPU / GPU / ZRAM / 性能调度，右侧显示当前状态） | `/proc`、`/sys` |
| **帧率** | **系统级帧率**（显示控制器实测，切到别的应用也有效）与**本应用帧率**并列显示；平均 / 最大帧耗时与卡顿计数；**30 秒 / 1 / 5 / 10 分钟 / 全部**分析窗口；**录制期间每秒留档**每核占用与频率、GPU、内存、功耗、温度；**历史会话列表**（SQLite 持久化，杀进程不丢，可录完整局游戏再回头分析）；**一键导出 CSV**；**跨应用实时监视** | `Choreographer`、drm sysfs |
| **电源** | **耗电统计（录制式）**：手动开始 / 结束，每秒采电量、电压、温度、电流，画**真实时间轴**电量曲线，算平均功耗与理论续航，按应用拆分耗电明细（曲线 / 列表双视图，顶栏可复制、删除本次记录）；**充电统计**：功耗与温度折线、电压 / 电流 / 功率 / 容量 / 循环次数 | `BatteryManager`、`UsageStatsManager`、`power_supply` |
| **内存详情** | 占用率与 SWAP 累积曲线；内存明细；**ZRAM 容量调整**（需先开「允许调整」闸门；0~8 GB，0 = 关闭交换分区）；swappiness 调节 | `/proc/meminfo`、`/proc/swaps`、`zram*` |
| **GPU 详情** | 频率 / 负载曲线；型号、当前频率、调速器、可用频率表；**调速器切换**；频率上限调节 | `kgsl-3d0` sysfs |
| **CPU 详情** | 总占用与每核柱状图；核心簇频率与量程；**调速器切换**；按核心选择目标后以**挡位**锁定频率（含调频策略组影响范围提示与写入回读校验） | `cpufreq` sysfs |
| **进程详情** | 全量进程列表（图标 / CPU% / 常驻内存 / 用户 / 状态），按 CPU 或内存排序，强制停止与结束进程 | root `top -b -n 1` |
| **性能调度** | 接管 **Uperf Game Turbo** 与 **A-SOUL Games Optimization**：电源档位切换（省电 / 均衡 / 性能 / 极速 / 自动 / 疯狂）、分应用模式规则增删改、A-SOUL 运行模式与分游戏亲和、两个守护进程重启 | 模块自身配置文件 |
| **系统开关** | `settings get/put` 类开关：显示点按操作 / 指针位置 / 强制 GPU 渲染 / 自由窗口 / 强制可调整大小 / 网络 ADB；**动画速度**三档（0.5x~2x，分别写过渡 / 窗口 / 动画时长三个缩放值）；**隐藏状态栏图标**多选 | `settings` 命令 |
| **提权管理** | Root 授权状态、**能力速查**（逐节点列出「可读 / 被拒 / 不存在」）、**探测明细**、重新探测 | `su` |
| **设置** | 主题（跟随系统 / 浅色 / 深色）与壁纸取色；功率校准（电流倍率 / 串联双电芯）；实时采样开关与间隔；**实时任务通知**（呈现方式 + 9 项显示配置 + 后台保活锚点）；Root / 使用情况访问 / 悬浮窗 / 通知权限；设备与内核信息 | — |

### 实时采样规格

- 采样间隔 **1 秒**；趋势累积保留 **1800 条（30 分钟）**，超出按滚动窗口丢弃最早一条
- 时间窗只影响**绘制范围**，不改变采样与留存策略；绘制统一降采样到 **60 个槽位**
- CPU 占用率由 `/proc/stat` 相邻两次采样求差；每核柱状图柱高 = 占用率、柱顶 = 当前频率
- 三处实时读数（抽屉置顶卡片 / 状态栏芯片 / 悬浮窗）统一按 **1 秒**刷新
- 帧率记录走 **SQLite 双表**（`session` + `history`），不受 1800 条内存上限约束

---

## 二、可以做什么

### 看

- **一眼判断设备是否正常**：概览页健康结论条把权限缺失、SoC 结温、电池温度、内存吃紧四项合成一句话
- **追某个指标的历史走势**：性能页切换 5 秒 ~ 30 分时间窗；长窗口看趋势，短窗口看抖动
- **定位谁在吃资源**：性能页进程摘要 → 进程管理，按 CPU 或内存排序
- **确认游戏 / 应用的流畅度**：帧率页开录制，跨应用切出去也能持续采集；录完回到列表按次回看

### 调

| 想做的事 | 入口 | 实测限制 |
|---|---|---|
| 锁 CPU 频率 | CPU 详情 → 选核心 → 选挡位 | 本机 `policy6`（大核 6,7）**生效**；`policy0`（0-5）被内核静默忽略，界面会明确报「未生效」 |
| 换 CPU / GPU 调速器 | CPU / GPU 详情 | 选项**从设备读取**（`available_governors`），不硬编码 |
| 限 GPU 频率上限 | GPU 详情 | 生效，带回读校验 |
| 调 ZRAM 容量 / swappiness | 内存详情 | ZRAM 默认**上锁**，需先开「允许调整」；0 = 关闭交换分区 |
| 改系统开关 / 动画速度 | 系统开关 | 写入 `settings` 成功即回读一致；但**部分开关 ColorOS 不响应**（见「已知限制」第 9 条） |
| 改 Uperf / A-SOUL 策略 | 性能 → 性能调度 | 只读写模块自己的配置文件，不替代模块 |

### 记

- **帧率留档**：帧率页开录 → 每秒同步留档每核占用与频率、GPU、内存、功耗、温度 → **落 SQLite**（重启可回看）→ **导出 CSV**（写入「下载 / OSPlus /」，无需存储权限）
- **耗电留档**：电源 → 耗电统计 → 开始录制 → 停止后看整段电量曲线、平均功耗、理论续航、按应用拆分的耗电明细 → 顶栏**复制**成可粘贴文本
- **跨应用监视**：设置里选呈现方式——**实时任务通知**（通知抽屉置顶卡片 + 状态栏芯片，无需权限）或**悬浮窗**（需悬浮窗权限）

### 清

- **清理物理内存**：概览页扫把按钮 → `sync; echo 3 > /proc/sys/vm/drop_caches`，清理前后各读一次 `/proc/meminfo` 求差，用真实数字回报释放量
- **清理交换分区**：逐设备 `swapoff` → `swapon`，把 swap 里的页换回物理内存后重建

---

## 三、重点代码

### 1. 一次 root 命令采集全部受限节点

Android 12+ 的 SELinux **不允许普通应用读取** `/proc/stat`、`/proc/swaps`、
`/sys/class/power_supply/battery/*`、`kgsl-3d0/*` 等节点。每秒为每个节点各起一次 `su` 不可接受，
因此把一秒内需要的全部节点合并成**一次**命令，用分隔符切分结果。

```kotlin
private const val SEP = "@@OSPLUS@@"
val script = buildString {
    appendLine("cat /proc/stat 2>/dev/null");   appendLine("echo '$SEP'")
    appendLine("cat /proc/meminfo 2>/dev/null"); appendLine("echo '$SEP'")
    // …其余节点
}
val parts = Shell.run(script, root = true).stdout.split(SEP)
```

### 2. `Shell` 的两条铁律

**必须有超时。** 真机抓到一条 `su -c <采样脚本>` 存活 **1 分 33 秒**，而 `waitFor()` 是阻塞的，
采样循环会永远停在原地、**不会自愈**——外部表现与「被冻结」几乎一样，极难定位。

```kotlin
private const val COMMAND_TIMEOUT_MS = 5_000L
if (!process.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
    process.destroyForcibly()
    return@runCatching CommandResult(false, "", "命令超时，已强制结束", -1)
}
```

**stdout / stderr 必须并发读，且读流体自己吞异常。** 串行读会在另一个管道被写满（约 64 KB）时
互相死锁；而超时分支 `destroyForcibly()` 关掉管道后，挂起的 `readText()` 会抛 IOException ——
它作为 `async` 子协程失败会**沿 Job 层级取消父协程、绕过外层 `runCatching`**，
最终把应用打崩（真机复现过 `APP CRASH`，堆栈停在 `Shell$run$2$1$stdout$1.invokeSuspend`）。

```kotlin
val stdout = async(Dispatchers.IO) {
    runCatching { process.inputStream.bufferedReader().readText() }.getOrDefault("")
}
```

> 规则：`async` / `launch` 的异常不受外层 `runCatching` 保护——要么让子协程永不失败，要么用 `supervisorScope`。

### 3. sysfs 的存在性判据：只能靠「try-read」

**本项目踩过最多次的一个坑，三种写法全部失效。**

`File.exists()`、`File.listFiles()`、shell 的 `[ -e ]` **都依赖 stat 父目录**，
而 Android 12+ SELinux 对厂商 sysfs 拒的正是**目录遍历**——于是三者一律返回
`false` / `null` / 恒假，节点被静默跳过，界面只显示「不可读」且**全程不报错**。

| 写法 | 失效场景 | 症状 |
|---|---|---|
| `File.exists()` | `power_supply` 下的容量节点 | 电池能量恒显示 0.0 Wh |
| `File.listFiles()` | `/sys/block`（列 zram 设备） | ZRAM 恒显示「未启用」，调整功能整体不可用 |
| `[ -e ]` | `/sys/class/drm/card0-sde-crtc-*/measured_fps` | 符号链接本身可读，但 target 不可达 → 判为「不存在」→ 整条 sysfs 链路被跳过 |

正确判据只有一个：**直接尝试读，能否解析出合法值交给 Kotlin 侧裁决**。
`[ -L ]` 也不行——它只证明链接在，不证明目标可读。

```kotlin
// ✗ 错误：`[ -e ]` 在 SELinux 拒绝父目录时恒假
append("if [ -e '$path' ]; then cat '$path'; fi; ")

// ✓ 正确：直接读，报错与「格式不认识」走同一条解析路径
append("printf 'P|%s|' '$path'; cat '$path' 2>&1 | head -1; ")
```

见 `Shell.readNode`、`SystemProbe`、`SysFpsDataSource.probeSysfs`。

### 4. 帧率兜底必须加物理合理性闸门

`service call SurfaceFlinger 1013` 这个 code **不是稳定 ABI**，Android 11+ 多数机型已失效——
返回的 8 位十六进制**不是帧计数**。不加判断直接差分，会把毫无物理意义的数字当帧率写进数据库：
本机实测同一段**静止画面**连续读出 **119.9 → 7.1 → 1.5**。

与其把垃圾值喂给图表（比显示「不可读」更糟——用户会拿它去分析卡顿），不如做物理约束：

```kotlin
private const val MAX_PLAUSIBLE_FPS = 200f      // 面板最高 165Hz，留 1.2 倍余量
private const val MIN_FRAME_INTERVAL_MS = 5f    // 120Hz ≈ 8.3ms，取 5ms 为硬下限

if (fps <= 0f || fps > MAX_PLAUSIBLE_FPS) return null   // 不可信 → 判定「不可读」
```

注意**不要**因此把整条兜底通道置为不可用——返回格式是能解析的，只是数值不可信，
关掉会让通道被永久废弃，而真实原因可能只是这一次窗口异常。

### 5. 功耗：`charge_counter` 滑动窗口求导

本机 `current_now` 单位为 mA 且空闲时恒为 0，直接换算得到 0 mW。改用 `charge_counter`（µAh）
做 **5 秒滑动窗口**求导——该节点更新粒度较粗，单次 1 秒差分经常为 0，拉长窗口才有稳定信号。

```kotlin
val dUah = (tick.chargeCounterUah - first.second).toFloat()
val currentUa = abs(dUah * 3600f / spanSec)
if (currentUa > 1_000f && tick.voltageMv > 0f) {
    lastPowerMw = tick.voltageMv * currentUa / 1_000_000f   // P = U × I
}
// 窗口未成熟时沿用上次结果，避免曲线在 0 与真值之间跳变
```

### 6. 温度：`thermal_zone` 编号没有语义

**不能用 `thermal_zone0` 当 SoC 结温。** 编号由内核设备树**注册顺序**决定，与物理位置无关。
本机（SM8750）实测 30+ 个 zone，`thermal_zone0` 是 `aoss-0`（Always-On 子系统，
读数偏低 3~4℃）；真正 CPU 结温是 `cpuss-0-0` / `cpu-0-N-M-K`，GPU 是 `gpuss-*`。

因此按 `type` 字符串**语义打分**选取，而不是按编号：

```kotlin
cpuss* = 100 > soc* = 95 > cpu-N-M-K = 60 + 簇号×5 > gpuss* = 40（兜底）
显式排除 aoss / vbat / bcl / camera / video / mdmss / pm*
```

修正后概览页 CPU 温度由 41℃ 变为 46~48℃，与真机发热体感一致。

### 7. 电池能量：必须用 `getLongProperty`

`BATTERY_PROPERTY_ENERGY_COUNTER` 以 **nWh** 为单位、API 21+ 可用，是系统侧唯一「直接测量」的能量读数。
但 28 Wh 折合 2.8e10 nWh —— 用 `getIntProperty` 会**溢出**拿到垃圾值。

```kotlin
val nWh = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)  // 不是 getIntProperty
val remainWh = nWh / 1_000_000_000f
val fullWh = remainWh * 100f / levelPercent        // 反推额定能量，口径与厂商一致
```

容量节点 `charge_full` / `charge_full_design` 的 raw **本身就是 µAh**，不要再除 1000。
验证方法：算出来的容量必须与机型标称值对得上（本机实测 5920 mAh），差 1000 倍就是单位错。

### 8. 所有内核写入都带回读校验

频率 / 调速器节点常被厂商策略接管，`echo` 正常返回但值不变。不校验就会出现
「拖了滑块却什么都没发生」的静默失败。

```kotlin
Shell.writeNode(path, target)
if (Shell.readNode(path)?.trim() == target) return true   // 回读一致才算生效
// 不一致时界面明确报「未生效：请求 X，内核回读仍为 Y」
```

调速器列表必须来自 `available_governors`，CPU 频率挡位必须来自 `scaling_available_frequencies`
—— 硬编码会造出「点了必然失败」的假选项。

### 9. 悬浮窗：窗口 `alpha` 恒为 1，视觉透明度交给 `View.alpha`

Android 12 起，`TYPE_APPLICATION_OVERLAY` 窗口若 `LayoutParams.alpha <= 0.8`
（`getMaximumObscuringOpacityForTouch()`），会被判定为「遮挡不足」，
**系统不再向它投递触摸事件** —— 悬浮窗变成只显示、不可交互。

```kotlin
params.alpha = 1f          // 输入层面永远是「足够遮挡」的触摸目标
overlayView.alpha = value  // 透明度只影响绘制，不影响输入分发
```

### 10. 保活锚点：1×1 不可见窗口顶住厂商冻结

ColorOS 会在应用退到后台约 5~10 秒后**用内核 cgroup freezer 冻结整个进程**，
前台服务不足以豁免、Root 也无法阻止（冻结期间进程执行不了任何代码）。
冻结框架的目标是**后台应用**——持有可见窗口的进程不属于后台。

```kotlin
WindowManager.LayoutParams(
    1, 1, TYPE_APPLICATION_OVERLAY,
    FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE or FLAG_NOT_TOUCH_MODAL,
    PixelFormat.TRANSLUCENT,
).apply { gravity = TOP or START; x = 0; y = 0; alpha = 1f }   // alpha 必须是 1.0
```

实测（60 秒后台 ×3 次）：无锚点 `T+6s` 起 `cgroup.freeze=1`、CPU 增量归零；
有锚点全程 `freeze=0`、CPU 持续增长、后台通知刷新 97 条 / 60 秒。
**息屏后仍会冻结**（窗口不再「可见」），亮屏自动恢复。

### 11. 真实时间轴的折线图

电量曲线的 X 轴按**毫秒比例映射**，不是把采样点均匀铺满整幅宽度——
等距铺满时录 1 分钟与录 10 分钟的曲线长得一模一样，斜率失去意义，
而这一页要回答的恰恰是「电量掉得快还是慢」。

```kotlin
fun xOf(ms: Long) = plotLeft + plotW * (ms / xMaxMs).coerceIn(0f, 1f)

fun autoXMaxMs(durationMs: Long): Long {          // 0~10 分钟起步
    val base = 10 * 60_000L
    if (durationMs <= base) return base
    return ((durationMs / 5 / 60_000L) + 1) * 5 * 60_000L   // 超出按 5 分钟向上取整
}
```

实测：录 104 条 / 1m47s 时曲线只占最左约 **13%** 宽度，X 轴刻度 `0:00 / 5:00 / 10:00`。

### 12. 通知提升：`MetricStyle` 的引入级别是 API 37

`api-versions.xml` 记为 `since="37.0"`。用 `BAKLAVA`(36) 门控在 Android 16 上会引用不存在的类
（`NoClassDefFoundError`，直接崩前台服务）。另外**官方明确 promoted 通知不得携带
`customContentView`（RemoteViews）**，因此提升分支与回退分支**绝不能复用同一个 `Notification.Builder`**
——否则会产出「既声明提升、又带自定义视图」的通知，被系统直接丢弃。

```kotlin
if (SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {   // 37，不是 BAKLAVA(36)
    runCatching { /* MetricStyle 提升分支，独立 Builder */ }
        .getOrElse { /* 退回 RemoteViews 卡片，另起一个 Builder */ }
}
```

### 13. 录制状态必须放在单例，不能放 ViewModel

Compose 的 `onDispose` 在**切换标签页**时同样会触发，早期把「停止录制」写在里面，
结果变成「一切到别的页面，记录就断了」。而录制要跨页面存活、还要能在应用退到后台后继续，
ViewModel 随 Activity 销毁而清理——因此 `FpsRecorder` / `PowerRecorder` 都做成单例，
页面只读不写，两边读写同一个 `StateFlow`。

### 14. 录制：先建会话，再开开关

`startFpsRecording` 曾经先调 `FpsRecorder.startRecording()`（**同步**置 `recording = true`），
再异步 `createSession`。但采样循环每秒都在跑，看到 `recording = true` 就立刻 `addSample`，
而此时 `activeSessionId` 还是 -1 → 被 `sessionId <= 0` 静默丢弃。

实测后果：一段 9.6 秒的记录**只落 5 条**（前 4 秒全丢），而内存里的 `_fpsRecords` 是完整的
——表现为「本次看得到、重启后少一截」，极难归因。

```kotlin
// ✓ 正确顺序：先在 IO 上建好会话并落定 id，再开开关
val sid = withContext(Dispatchers.IO) { fpsStore.createSession(pkg) }
activeSessionId = sid
if (sid > 0L) {
    closeFpsSession()
    FpsRecorder.startRecording()   // 建会话失败时拒绝开录，不然会录一段永不落库的数据
}
```

修复后同样时长落 **10 条**，`sample_count` 与实际条数完全一致。

### 15. 内存态 vs 持久化态：UI 只能有**一个**下游数据源

帧率页同时存在两套数据：内存的 `records`（实时）与 SQLite 读出的 `viewingSamples`（历史会话）。
早期只有双轴图做了历史切换，其余 8 处图表（帧率曲线 / 帧耗时 / CPU / GPU / 内存 / 功耗 /
记录汇总 / 各核心占用）**全部写死读内存的 `windowed`**；而整个图表区是否渲染，
又只判 `records.isEmpty()`。

结果是重启后打开历史会话：双轴图有数据、其余图全空，甚至整个图表区被判成空状态、
显示「开启开始记录后…」的提示 —— 正是「重启后记录无法查看」的直接原因。

```kotlin
// 页面顶部定义统一下游数据集，所有图表一律引用它
val src = if (viewingSamples.isNotEmpty()) viewingSamples else windowed
val viewing = viewingSamples.isNotEmpty()
// 空状态判据必须同时看两边
} else if (records.isEmpty() && viewingSamples.isEmpty()) {
```

> 教训：两套数据源混用时，UI 里每处引用都要问一遍「这里该是哪一个」。
> 漏几处就会表现为「一半有数据一半空」，比全空更难排查。

### 16. 液态玻璃：原版组件的换装与适配

全部玻璃控件都是 Kyant0 **AndroidLiquidGlass** 的移植件（`ui/liquid/`，源码逻辑保持原样）；
`ui/components/LiquidGlass.kt` 只做**适配层**，不参与渲染。

**为什么必须有适配层**：原版签名与本工程的三处约束不一致，而约定是**不改上游文件**。

| 差异 | 适配层的处理 |
|---|---|
| 卡片内控件拿不到根 `backdrop` | 统一传 `emptyBackdrop()`，并补一层官方容器色——空 backdrop 没有可折射的内容，不补色按钮会退化成一行普通文字，完全看不出可点 |
| 原版没有 `enabled`，且 `isInteractive = false` **不拦截点击** | 禁用视觉用 `modifier.alpha()`；「无 Root 不执行」这类守卫必须写在 `onClick` 里，不能指望 `isInteractive` 拦下来 |
| 原版把 `selectedTabIndex` / `value()` 当 `remember` 的 key | 用 `rememberUpdatedState` + `remember` 钉死 lambda 身份，否则每次重组都重建内部状态，表现是「拖动松手后不切换」 |

**按钮没有适配层**：页面内按钮、顶栏圆形 / 胶囊按钮、`ChoiceChip` 一律由调用点**直接引用**
原版 `LiquidButton`。原版把 `height(48.dp)` 与 `padding(horizontal = 16.dp)` 写死在组件内部，
外部 modifier 覆盖不了，顶栏图标按钮因此必然被撑成 51×48 的椭圆。解法是给原版加两个
**带默认值**的参数（`height` / `horizontalPadding`），默认值即原版值；
顶栏传 `size(36.dp) + height = 36 + horizontalPadding = 0`，正方形加零内边距即得正圆。

**动作组的胶囊要自己跟**：原版胶囊位置**完全**由 `selectedTabIndex` 驱动，动作组恒传 -1
时胶囊永久停在第一格——点「导出 CSV / 清空记录」会出现「动作执行了、胶囊却不动」。
适配层补回 `lastPressed` 语义：动作组跟随最近按下的项，选择组仍由外部状态决定。

**着色参数怎么选**：原版 `surfaceColor` 是 `drawRect` 不透明覆盖（设了就永远有底色），
`tint` 是 `BlendMode.Hue` 染色 + `0.75` alpha 覆盖。空 backdrop 上 Hue 染色无效，
但 0.75 alpha 覆盖一定有效——所以未选中态用 `tint` 保底可见、选中态用 `surfaceColor` 淡染。

---

### 17. 预测性返回：**用 `PredictiveBackHandler`，别用 `NavigationBackHandler`**

**背景**：Android 13 引入「预测性返回桌面」，Android 14 扩展到应用内。开启后，
从屏幕边缘右滑的过程中系统要**提前展示**将要返回的上一页；手势未完成时用户
能看到那一页正从左侧回来，也可以把手指拖回去取消。前提是 Manifest 里
`android:enableOnBackInvokedCallback="true"`——本工程从 2.5.0 起就已置位。

#### 17.1 致命弯路：`NavigationBackHandler` 会**抑制系统动画**

2.8.0 初版用的是 `androidx.navigationevent.compose.NavigationBackHandler`，
理由是它「也能拿到进度」（`state.transitionState.latestEvent.progress`）。
**这条路是错的，而且症状极具迷惑性**：代码逻辑全对、日志里 `progress` 从
0.33 平滑递增到 0.45、`onBackCompleted` 也正常触发，**但屏幕上纹丝不动**。

两个叠加的原因：

1. **它会顶掉系统动画。** 官方文档明确写明：应用一旦注册了
   `PRIORITY_DEFAULT` / `PRIORITY_OVERLAY` 的返回回调，
   **系统自己的预测性返回动画就不会再播**，必须由应用自己画完全过程。
   `NavigationBackHandler` 注册的正是默认优先级。
2. **它不是动画 API，进度不好接。** 用它驱动 `graphicsLayer` 时极易踩
   「状态读取没触发 layer 失效」的坑（见 17.2），结果就是既没有系统动画、
   也没有自绘动画，只剩松手瞬间的跳变。

官方给 Compose **动画**场景准备的 API 是
`androidx.activity.compose.PredictiveBackHandler`
（依赖 `androidx.activity:activity-compose:1.8.0+`，本工程 1.13.0）：

```kotlin
PredictiveBackHandler(enabled = isBackEnabled) { progressFlow ->
    try {
        progressFlow.collect { backEvent ->
            state.progress = backEvent.progress      // 跟手进度 0f~1f
            state.touch = Offset(backEvent.touchX, backEvent.touchY)
        }
        onBack()                                     // Flow 正常结束 = 确认返回
    } catch (e: CancellationException) {
        state.isInProgress = false                   // 手势取消 → 回弹
        throw e
    }
}
```

`BackEventCompat` 提供的字段：

| 字段 | 含义 |
|---|---|
| `progress` | 0f ~ 1f，手势越过阈值的比例 |
| `touchX` / `touchY` | 手指屏幕坐标，可驱动「返回箭头跟手」 |
| `swipeEdge` | `EDGE_LEFT` / `EDGE_RIGHT` / `EDGE_NONE` |

#### 17.2 致命弯路二：`progress` 读在组合体里 = 屏幕不刷新

```kotlin
// ❌ 错误：只在组合那一刻取值一次，手势期间永不变化
val backProgress = backState.progress
Box(Modifier.graphicsLayer { translationX = backProgress * w })

// ✅ 正确：在 lambda 内部读，lambda 每次重绘都执行，读到的永远是最新值
Box(Modifier.graphicsLayer {
    val p = backState.progress
    translationX = p * w
})
```

`graphicsLayer` 的 lambda 在**绘制阶段**执行，在里面读 snapshot 状态会让 layer
正确失效并重绘；写在**组合函数体**里则只是个静态快照。这一条是「日志有进度、
屏幕没反应」的根因，排查时优先查它。

> 注：`snapshotFlow` 收尾动画用 `Animatable` 把进度平滑推回 0；
> 手势中则直接吃 `backEvent.progress`（零延迟跟手）。
> 两条写入路径必须**互斥**，否则收尾动画会把实时值往回拽，表现为跟手迟滞。
> 另外 `Animatable.stop()` 是 `suspend` 函数，不能在非协程回调里调。

#### 17.3 两级页面必须分层，否则让开时露底

改造前 `screenKey = route ?: tabKey(rootTab)`，一级页与二级页是**同一个插槽的两个状态**。
覆盖层让开时底下一片空白（`PageBackground` 也被一起移走了），接预测性返回必然穿帮。

修法是把层级拆开：

```
Box
├── 底层：一级页（常驻，永不卸载）  ← 手势让开时用户看到的就是它
│   ├── PageBackground
│   ├── RootTopBar
│   ├── AnimatedContent(rootTab)  ← 一级页自己的切页动画
│   └── LiquidBottomBar           ← 属于这一层，随浮层让开同步淡入
└── 上层：路由浮层（route != null 时才存在）
    ├── PageBackground
    ├── OsTopBar(带返回箭头)
    └── AnimatedContent(screenKey)
```

浮层的跟手变换全在 `graphicsLayer` 里，不触发重组，也不影响子内容布局：

```kotlin
translationX = p * screenWidthPx * 0.32f             // 屏幕宽 32%
scaleX = scaleY = 1f - p * 0.06f                      // 轻微缩小，给底层露出感
shape = RoundedCornerShape(28.dp * p)                 // 圆角随进度长出来
clip = p > 0.001f                                     // 静止态不裁，四角保持完整
alpha = 1f - p * 0.12f                                // 轻微压暗，强化被推走的层次
```

位移基准用 `LocalConfiguration.screenWidthDp` 换算，**不要读 Layout 尺寸**——
后者首帧拿不到，第一次手势位移会是 0。

#### 17.4 `isBackEnabled = false` 不是「禁用返回」

它表示「本处理器不接管」，系统会把这次手势回落给默认行为——在一级页边缘右滑时，
正是靠它拿到**预测性返回桌面**。所以判据写
`route != null || rootTab != RootTab.Overview`，而不是无条件 true。

**顶栏返回按钮与手势共用同一条路径**：两者都调用同一个 `popRoute()`，
不会出现「按钮返回有动画、手势返回没有」这类不一致。

#### 17.5 国产 ROM 有一道私有总闸（重要）

一加 / ColorOS 上实测「代码全对但没有任何预测性返回」时，**先查这个**：

```bash
adb shell settings get secure oplus_third_part_apps_predictive_back   # 出厂默认 0
adb shell settings put secure oplus_third_part_apps_predictive_back 1 # 开启后重启应用
```

它**不是 Manifest 能控制的**，是 ROM 层的用户设置。关着时系统**完全不给第三方应用
下发手势事件**，`PredictiveBackHandler` 一个事件都收不到。同一个坑也存在于
HyperOS / OriginOS 等国产 ROM（开关名不同），换机型时先 `settings list secure`
搜一遍相关关键字，不要先怀疑自己的代码。

#### 17.6 动画幅度做成可调项，而不是硬编码常量

跟手动画的五个幅度**没有客观最优解**——屏幕尺寸、刷新率、个人对「跟手」的容忍度
都会影响观感，拍一个常量只能服务一部分人。因此全部开放到
**设置 → 预测性返回**，可在应用内实时调整：

| 参数 | 默认 | 范围 | 调大的效果 |
| --- | --- | --- | --- |
| 位移比例 | 0.32 | 0~1 | 底层露出更多、更「跟手」 |
| 缩小量 | 0.06 | 0~0.3 | 后退层次更强，超过 0.15 显得夸张 |
| 圆角上限 | 28 dp | 0~64 | 覆盖层四角更圆 |
| 回弹耗时 | 200 ms | 60~800 | 取消后回弹更柔和但偏慢 |
| 压暗量 | 0.12 | 0~0.5 | 被推走的层次感更强 |

两类实现细节值得记：

1. **参数不是常量，所以不能写进 `graphicsLayer` 的闭包捕获**。`graphicsLayer` 的
   lambda 会在每帧重绘时执行，若在里面读一个组合期固定的 `data class` 实例，
   改设置后要等重组才生效。这里在**手势开始时**重读一次偏好并写入 `state.tuning`，
   既保证「改完设置 → 返回 → 滑一次」就是新值，又不会在滑动中途被改设置打断。
2. **输入框用字符串状态托管**，不直接绑 `Float`。直接绑会让「输入 `0.`」这类
   中间态被解析失败吞掉，用户打到一半字就跳变。保存时再 `toFloatOrNull()`，
   解析失败保持原值不动。

---

## 四、工程结构

```
app/src/main/java/com/osplus/tools/
├── MainActivity.kt              # 唯一 Activity，edge-to-edge + 预测性返回 + 请求最高刷新率
├── OsPlusApplication.kt         # 通知渠道（含历史遗留通道清理）
├── core/
│   ├── Shell.kt                 # su/sh 执行封装（root 探测缓存、超时、并发读流）
│   ├── SystemProbe.kt           # 一次 root 命令采集全部受限节点；census() 节点普查
│   ├── SystemExtrasDataSource.kt # 负载 / 网络 / 磁盘 IO / IO 压力
│   ├── CpuDataSource.kt         # CPU 信息、簇识别、增量占用率、频率与调速器、温度语义选取
│   ├── GpuDataSource.kt         # kgsl / devfreq 多路径探测与频率控制
│   ├── MemDataSource.kt         # 内存、SWAP、ZRAM（shell glob 列举）、swappiness
│   ├── BatteryDataSource.kt     # 电池信息 + 能量读数
│   ├── ProcessDataSource.kt     # root top 全量进程列表
│   ├── PerfSchedDataSource.kt   # Uperf / A-SOUL 模块的配置读写与进程重启
│   ├── PowerStatsDataSource.kt  # 「使用情况访问」权限网关
│   ├── SysFpsDataSource.kt      # 系统级帧率（drm measured_fps，四级降级 + 合理性闸门）
│   ├── FpsWatchStore.kt         # 帧率记录持久化：SQLite 双表（session + history）
│   ├── SystemTogglesDataSource.kt # settings get/put 类开关定义与读写
│   ├── LiveMetrics.kt           # 实时指标快照（通知 / 悬浮窗的数据源）
│   ├── LiveNotif.kt             # 实时任务通知：呈现方式、显示项注册表、保活开关
│   ├── Preferences.kt           # SharedPreferences
│   ├── FpsOverlayState.kt       # 悬浮窗开关与不透明度的跨层状态
│   ├── FpsRecorder.kt           # Choreographer 逐帧统计 + 记录会话状态
│   ├── PowerRecorder.kt         # 耗电录制：逐秒累积、应用侧加权、功耗与续航计算
│   ├── PrivilegeMode.kt         # 提权方式枚举（仅 Root）+ 历史存储值兼容
│   ├── PrivilegeManager.kt      # 通道探测与选择：mode（当前生效）/ selection（用户点选）
│   ├── PrivilegeBackend.kt      # 提权后端抽象与 Root 实现
│   ├── PrivilegeLog.kt          # 提权链路日志
├── model/Models.kt              # 数据模型
├── vm/DeviceViewModel.kt        # 1 秒采样循环 + 累积趋势缓冲 + 全部控制入口
├── ui/
│   ├── OsPlusApp.kt             # 根布局：一级页常驻底层 + 路由浮层 + 预测性返回 + 液态玻璃底栏
│   ├── theme/                   # OsTokens（设计令牌）、Theme
│   ├── liquid/                  # Kyant0 原版组件移植件：LiquidButton / LiquidBottomTabs /
│   │                            #   LiquidBottomTab / LiquidToggle / LiquidSlider / LiquidUtils
│   ├── components/              # Surfaces / LiquidGlass（底栏·开关·滑块的适配层）/
│   │                            #   GlassShaders / Charts / Navigation / PrivilegeComponents /
│   │                            #   PredictiveBack（官方 PredictiveBackHandler 封装 + 参数注入）/ Common
│   └── screen/                  # Overview / Perf / Fps / Power / Settings /
│                                #   SystemToggles / Privilege / PredictiveBack（动画参数）/
│                                #   LiquidLab / 详情页 / 性能调度
├── service/FpsOverlayService.kt # 跨应用监视前台服务：通知 / 悬浮窗 / 保活锚点
└── receiver/BootReceiver.kt

app/src/test/java/com/osplus/tools/
├── DownsampleTest.kt            # 趋势降采样
├── FpsRecordTest.kt             # 帧率记录与 CSV 行格式
└── GpuDataSourceTest.kt         # GPU 频率单位判定
```

**62 个 Kotlin 文件 / 19128 行**（main 59 + test 3）。

---

## 五、构建与发布

环境：JDK 17、Android SDK（platform 37 + build-tools 37.0.0）、Gradle 9.7.1、AGP 9.4.1、Kotlin 2.4.20

```bash
cd C:/Work/OSPlus
export JAVA_HOME="C:/Program Files/Amazon Corretto/jdk17.0.20_10"
/c/Work/gradle/gradle-9.7.1/bin/gradle --no-daemon assembleDebug      # 调试包
/c/Work/gradle/gradle-9.7.1/bin/gradle --no-daemon assembleRelease    # 发布包
/c/Work/gradle/gradle-9.7.1/bin/gradle --no-daemon testDebugUnitTest  # 单元测试
```

> AGP 9 起内置 Kotlin 支持，`org.jetbrains.kotlin.android` 插件必须移除，
> 仅保留 `org.jetbrains.kotlin.plugin.compose`。

**提权方式：仅 Root**

OSPlus 的全部控制功能（CPU 调频、进程管理、性能调度、帧率读取）都需要 Root 身份。
2.2.0 期间曾引入 Shizuku（uid 2000）与 ADB（无线配对 / 电脑脚本 daemon）
两条非 Root 通道，**实测证明在本应用的功能需求下全部失效**：

- CPU 调频节点 `scaling_governor` / `scaling_max_freq` 属主 root、
  SELinux 上下文 `sysfs_devices_system_cpu`，非 root 写入返回 `Permission denied`；
- 性能调度依赖的 `/data/adb/modules`、`/data/adb/naki` 对 shell 身份不可搜索；
- `measured_fps` 是 `Permission denied`、`service call SurfaceFlinger` 是
  `Operation not permitted`。

这是权限模型本身的结果，换任何 daemon 机制都绕不过 SELinux。因此两条通道
连同其依赖（Shizuku SDK、BouncyCastle、Conscrypt、`core/adb/`、`assets/adb/`）
已被整体移除，只保留 Root。

**「shell 拒绝」不等于「应用内拒绝」**：本机 `adb shell` 下没有 `su`（KernelSU 不提供），
用 `adb shell su -c` 探测会得出错误结论。应用内 root（`uid=0 context=u:r:ksu:s0`）
能读到 shell 被拒的节点（如 `kgsl-3d0/gpu_busy_percentage`、`power_supply/battery/*`）。
**判断链路是否正常，只认应用内实测。**

真机核查：

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
# 实时任务通知是否被提升为置顶卡片
adb shell dumpsys notification --noredact | grep -E "PROMOTED|shortCritical"
# 进程是否被系统冻结（1 = 已冻结）
adb shell "cat /sys/fs/cgroup/apps/uid_<uid>/pid_<pid>/cgroup.freeze"
# 拉帧率库（必须用 exec-out，adb shell cat 走 pty 会做 CRLF 转换污染二进制）
adb exec-out "run-as com.osplus.tools cat databases/osplus_fps_watch" > fps.db
```

**发布流程**：每个版本打 tag `vX.Y.Z` + 建 Release（标题 `OSPlus X.Y.Z — <主题>`）+ 附件 `OSPlus-X.Y.Z.apk`。
若本机 `github.com` 不可达而 `api.github.com` 正常，普通 `git push` 会失败，
需改走 Git Data API（`blobs → tree → commit → PATCH ref`），并**在 `gh release create` 之后比对附件 sha256**。

---

## 六、已知限制

1. **需要 Root 才能完整工作**。无 Root 时仅内存与 CPU 频率可读，CPU 占用率、GPU、功耗、
   进程列表、所有控制功能均不可用（界面会给出提示）。
2. **后台实时刷新受厂商冻结策略限制**。ColorOS 会在后台约 5~10 秒后冻结整个进程，
   前台服务不足以豁免、Root 也无法阻止。「保活锚点」可解决亮屏场景（需悬浮窗权限），
   息屏后仍会暂停，亮屏自动恢复。
3. **耗电录制里没有一项是系统直接读出来的**。Android 不提供功耗、按应用耗电量、
   实时前台时长这三类接口，数值分别由「电压 × 电流」「CPU 占用加权」「UsageStats 差分」推算，
   界面已逐项标明来源与局限。无 Root 时平均功耗退化为电量差法，短录制误差可达 ±100%，
   因此录制不足 5 分钟时不给结论。
4. **实时任务通知最多显示 3 项指标**，这是系统 `MetricStyle` 的平台限制；
   字体与列宽由系统模板决定，应用无法调整。
5. **趋势数据是滚动窗口**，上限 1800 条（30 分钟）。需要更长留档请用帧率页的录制功能
   （SQLite 持久化，无条数上限）并导出 CSV。
6. **ZRAM 容量调整会重建交换分区**，正在使用交换区的应用可能出现短暂卡顿。
   需先开启「允许调整」闸门，默认关闭；部分机型内核不允许运行时改小 zram。
7. **性能调度页依赖第三方模块**。Uperf Game Turbo 与 A-SOUL Games Optimization
   均为独立 Magisk 模块，未安装时对应卡片只显示「未检测到模块」。
8. **单核调频在物理上不可实现**。本机 `cpu0..5` 的 `scaling_max_freq` 是同一 inode，
   写任意一个都影响整组，且 `policy0` 的写入被内核静默忽略——界面会明确列出
   调频策略组影响范围并做回读校验，而不是做一个看起来能单核调节的假开关。
9. **部分 `settings` 开关写入成功但系统不响应**。ColorOS 用自有后端接管了悬浮通知、
   深色模式、自动旋转、呼吸灯、显示刷新率这几项——`settings put` 返回成功、回读也一致，
   但**系统行为不变**。这类「能拨但没效果」的开关已从「系统开关」页整体移除
   （2.6.5），宁可不提供，也不给假开关。
10. **系统级帧率是约 0.5 秒窗口的平均值**，界面静止时读数天然是个位数（1~8 FPS），
    属正常现象。判断链路是否正常看「有值 + 随屏幕活动变化」，不看数字大小。
    该值读不到时会退回本应用自身的 `Choreographer` 值，并在界面标明当前用的是哪一路。
11. **预测性返回的跟手动画只在 Android 14（API 34）及以上完整生效**。
    API 33 只提供「预测性返回桌面」与返回完成的终态回调，滑动过程中的进度
    不下发，因此覆盖层不会有跟手位移（返回本身仍然正常）。本机
    （PJZ110 / Android 17）为完整支持。
12. **部分国产 ROM 默认关闭「第三方应用预测性返回」**。一加 / ColorOS 上是
    `secure oplus_third_part_apps_predictive_back`（出厂 0），关着时系统**完全不给
    第三方应用下发手势事件**，应用侧无任何代码可以绕过。需要用户在系统设置里开启，
    或 `settings put secure oplus_third_part_apps_predictive_back 1` 后重启应用。
    详见 §17.5。这是系统级开关，不属于应用缺陷。

---

## 七、相关文档

| 文档 | 内容 |
|---|---|
| [`docs/README.md`](docs/README.md) | 液态玻璃悬浮导航栏的完整实现（两层 backdrop、AGSL 折射 SDF 位移场）；实时任务通知的可配置化、通道迁移、后台冻结约束与保活锚点的实测记录 |

> 2.7.0 起仓库已移除 `dist/`（原为第三方 Magisk 模块的打包交付物，167 个文件，非本工程代码）。
