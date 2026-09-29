# OSPlus · Android 性能监视与调优工具

包名 `com.osplus.tools` ｜ 版本 **2.1.0**（versionCode 15）｜ minSdk 33 (Android 13) ｜ targetSdk 36 ｜ compileSdk 37

完全自研的**本地**性能监视与调优工具。数据全部来自 `/proc`、`/sys` 与系统 API，
**不做任何云端上报**；涉及内核写入的功能全部经 Root 执行。

---

## 一、有什么功能

### 页面总览

**统一顶栏**（左侧标题 + 右侧页面动作）+ 底部悬浮导航分
**概览 / 性能 / 帧率 / 电源** 四个一级页；**设置**挂在顶栏右侧。
概览与性能页的卡片可下钻到 **内存 / GPU / CPU / 进程 / 性能调度** 五个二级页。

| 一级页 | 一句话 |
|---|---|
| **概览** | 健康结论条 + 2×2 指标网格（CPU / GPU / 内存圆环 + 帧率折线）+ 设备概况 |
| **性能** | 进程摘要 + 5 秒~30 分可选时间窗的六条趋势 + 每核柱状图 + 四个调优入口 |
| **帧率** | 实时 FPS / 帧耗时 / 卡顿计数 + 多档分析窗口 + 录制留档 + CSV 导出 |
| **电源** | 电池态势 + **耗电统计录制** + 充电统计 + 充电控制 |

### 各页能力

| 页面 | 能做什么 | 数据来源 |
|---|---|---|
| **概览** | 健康结论条按权限 / SoC 结温 / 电池温度 / 内存四项判据给出「算不算正常」；CPU / GPU / 内存用**圆环**表达水位（名称、百分比、说明行全收在环心）；第四格是**帧率折线**——一个瞬时百分比会把「稳定 60 帧」和「在 60 与 30 之间来回跳」画成同一个数字。四个动作（清理内存 / 清理交换 / 记录帧率 / 设置）收在顶栏右侧，滚到哪都够得着 | `/proc`、`/sys` |
| **性能** | 进程摘要置顶（点进进程管理）；**5 秒 / 1 分 / 5 分 / 30 分**时间窗分段控件；CPU 总占用、内存、GPU 频率、整机功耗、实时帧率、电池温度六条趋势；每核占用与频率柱状图；调优入口（CPU / GPU / ZRAM / 性能调度，右侧显示当前状态） | `/proc`、`/sys` |
| **帧率** | Choreographer 实时 FPS、平均 / 最大帧耗时、卡顿计数；**30 秒 / 1 / 5 / 10 分钟 / 全部**分析窗口；**录制期间每秒同步留档**每核占用与频率、GPU、内存、功耗、温度；**一键导出 CSV**；**跨应用实时监视**（呈现方式见「设置」）；系统 `dumpsys gfxinfo` 明细 | 系统 API |
| **电源** | **耗电统计（录制式）**：手动开始 / 结束，每秒采电量、电压、温度、电流，画**真实时间轴**电量曲线，算平均功耗与理论续航，按应用拆分耗电明细（曲线 / 列表双视图，顶栏可复制、删除本次记录）；**充电统计**：功耗与温度折线、电压 / 电流 / 功率 / 容量 / 循环次数；**充电控制**：充电开关、充电电流上限 | `BatteryManager`、`UsageStatsManager`、`power_supply` |
| **内存详情** | 占用率与 SWAP 累积曲线；内存明细；**ZRAM 容量调整**（需先开「允许调整」闸门；0~8 GB，0 = 关闭交换分区）；swappiness 调节 | `/proc/meminfo`、`/proc/swaps`、`zram*` |
| **GPU 详情** | 频率 / 负载曲线；型号、当前频率、调速器、可用频率表；**调速器切换**；频率上限调节 | `kgsl-3d0` sysfs |
| **CPU 详情** | 总占用与每核柱状图；核心簇频率与量程；**调速器切换**；按核心选择目标后以**挡位**锁定频率（含调频策略组影响范围提示与写入回读校验） | `cpufreq` sysfs |
| **进程详情** | 全量进程列表（图标 / CPU% / 常驻内存 / 用户 / 状态），按 CPU 或内存排序，强制停止与结束进程 | root `top -b -n 1` |
| **性能调度** | 接管 **Uperf Game Turbo** 与 **A-SOUL Games Optimization**：电源档位切换（省电 / 均衡 / 性能 / 极速 / 自动 / 疯狂）、分应用模式规则增删改、A-SOUL 运行模式与分游戏亲和、两个守护进程重启 | 模块自身配置文件 |
| **设置** | 主题（跟随系统 / 浅色 / 深色）与壁纸取色；实时采样开关与间隔；**实时任务通知**（呈现方式 + 9 项显示配置 + 后台保活锚点）；Root / 使用情况访问 / 悬浮窗 / 通知权限；设备与内核信息 | — |

### 实时采样规格

- 采样间隔 **1 秒**；趋势累积保留 **1800 条（30 分钟）**，超出按滚动窗口丢弃最早一条
- 时间窗只影响**绘制范围**，不改变采样与留存策略；绘制统一降采样到 **60 个槽位**
- CPU 占用率由 `/proc/stat` 相邻两次采样求差；每核柱状图柱高 = 占用率、柱顶 = 当前频率
- 三处实时读数（抽屉置顶卡片 / 状态栏芯片 / 悬浮窗）统一按 **1 秒**刷新

---

## 二、可以做什么

### 看

- **一眼判断设备是否正常**：概览页健康结论条把权限缺失、SoC 结温、电池温度、内存吃紧四项合成一句话
- **追某个指标的历史走势**：性能页切换 5 秒 ~ 30 分时间窗；长窗口看趋势，短窗口看抖动
- **定位谁在吃资源**：性能页进程摘要 → 进程管理，按 CPU 或内存排序
- **确认游戏 / 应用的流畅度**：帧率页开录制，跨应用切出去也能持续采集

### 调

| 想做的事 | 入口 | 实测限制 |
|---|---|---|
| 锁 CPU 频率 | CPU 详情 → 选核心 → 选挡位 | 本机 `policy6`（大核 6,7）**生效**；`policy0`（0-5）被内核静默忽略，界面会明确报「未生效」 |
| 换 CPU / GPU 调速器 | CPU / GPU 详情 | 选项**从设备读取**（`available_governors`），不硬编码 |
| 限 GPU 频率上限 | GPU 详情 | 生效，带回读校验 |
| 调 ZRAM 容量 / swappiness | 内存详情 | ZRAM 默认**上锁**，需先开「允许调整」；0 = 关闭交换分区 |
| 控制充电 | 电源 → 充电控制 | 依赖内核是否暴露节点；未暴露时开关置灰 |
| 改 Uperf / A-SOUL 策略 | 性能 → 性能调度 | 只读写模块自己的配置文件，不替代模块 |

### 记

- **帧率留档**：帧率页开录 → 每秒同步留档每核占用与频率、GPU、内存、功耗、温度 → **导出 CSV**（写入「下载 / OSPlus /」，无需存储权限）
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

### 3. 功耗：`charge_counter` 滑动窗口求导

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

### 4. 电池节点：用 root 的 `[ -f ]` 代替 `File.exists()`

**本项目最隐蔽的一个坑。** Android 12+ 对厂商 sysfs 目录的 SELinux 拒绝会让
`File.exists()` 返回 **false**，节点被当成「不存在」整个跳过、永不提权。
表现是「电池能量显示 0.0 Wh」而**全程不报任何错**。

```kotlin
// ✗ 错误：目录列举被拒时 exists() 恒为 false，节点被静默跳过
if (!File("$dir/$name").exists()) continue

// ✓ 正确：把判断交给 root shell，同时把 N 个节点压成一次 su
"for d in $dirs; do if [ -f \$d/$name ]; then cat \$d/$name 2>/dev/null; break; fi; done"
```

同样的写法在 `ChargeController` 里也要用——否则充电控制在被保护的机型上会整体失效，
而界面只显示「未检测到可用节点」，看不出是权限问题。

### 5. 电池能量：必须用 `getLongProperty`

`BATTERY_PROPERTY_ENERGY_COUNTER` 以 **nWh** 为单位、API 21+ 可用，是系统侧唯一「直接测量」的能量读数。
但 28 Wh 折合 2.8e10 nWh —— 用 `getIntProperty` 会**溢出**拿到垃圾值。

```kotlin
val nWh = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)  // 不是 getIntProperty
val remainWh = nWh / 1_000_000_000f
val fullWh = remainWh * 100f / levelPercent        // 反推额定能量，口径与厂商一致
```

容量节点 `charge_full` / `charge_full_design` 的 raw **本身就是 µAh**，不要再除 1000。
验证方法：算出来的容量必须与机型标称值对得上（本机实测 5920 mAh），差 1000 倍就是单位错。

### 6. 所有内核写入都带回读校验

频率 / 调速器 / 充电节点常被厂商策略接管，`echo` 正常返回但值不变。不校验就会出现
「拖了滑块却什么都没发生」的静默失败。

```kotlin
Shell.writeNode(path, target)
if (Shell.readNode(path)?.trim() == target) return true   // 回读一致才算生效
// 不一致时界面明确报「未生效：请求 X，内核回读仍为 Y」
```

调速器列表必须来自 `available_governors`，CPU 频率挡位必须来自 `scaling_available_frequencies`
—— 硬编码会造出「点了必然失败」的假选项。

### 7. 悬浮窗：窗口 `alpha` 恒为 1，视觉透明度交给 `View.alpha`

Android 12 起，`TYPE_APPLICATION_OVERLAY` 窗口若 `LayoutParams.alpha <= 0.8`
（`getMaximumObscuringOpacityForTouch()`），会被判定为「遮挡不足」，
**系统不再向它投递触摸事件** —— 悬浮窗变成只显示、不可交互。

```kotlin
params.alpha = 1f          // 输入层面永远是「足够遮挡」的触摸目标
overlayView.alpha = value  // 透明度只影响绘制，不影响输入分发
```

### 8. 保活锚点：1×1 不可见窗口顶住厂商冻结

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

### 9. 真实时间轴的折线图

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

### 10. 通知提升：`MetricStyle` 的引入级别是 API 37

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

### 11. 录制状态必须放在单例，不能放 ViewModel

Compose 的 `onDispose` 在**切换标签页**时同样会触发，早期把「停止录制」写在里面，
结果变成「一切到别的页面，记录就断了」。而录制要跨页面存活、还要能在应用退到后台后继续，
ViewModel 随 Activity 销毁而清理——因此 `FpsRecorder` / `PowerRecorder` 都做成单例，
页面只读不写，两边读写同一个 `StateFlow`。

---

## 四、工程结构

```
app/src/main/java/com/osplus/tools/
├── MainActivity.kt              # 唯一 Activity，edge-to-edge + 预测性返回 + 请求最高刷新率
├── OsPlusApplication.kt         # 通知渠道（含历史遗留通道清理）
├── core/
│   ├── Shell.kt                 # su/sh 执行封装（root 探测缓存、超时、并发读流）
│   ├── SystemProbe.kt           # 一次 root 命令采集全部受限节点
│   ├── CpuDataSource.kt         # CPU 信息、簇识别、增量占用率、频率与调速器控制
│   ├── GpuDataSource.kt         # kgsl / devfreq 多路径探测与频率控制
│   ├── MemDataSource.kt         # 内存、SWAP、ZRAM、swappiness
│   ├── BatteryDataSource.kt     # 电池信息 + 能量读数 + ChargeController 充电控制
│   ├── ProcessDataSource.kt     # root top 全量进程列表
│   ├── PerfSchedDataSource.kt   # Uperf / A-SOUL 模块的配置读写与进程重启
│   ├── PowerStatsDataSource.kt  # 「使用情况访问」权限网关
│   ├── LiveMetrics.kt           # 实时指标快照（通知 / 悬浮窗的数据源）
│   ├── LiveNotif.kt             # 实时任务通知：呈现方式、显示项注册表、保活开关
│   ├── Preferences.kt           # SharedPreferences
│   ├── FpsOverlayState.kt       # 悬浮窗开关与不透明度的跨层状态
│   ├── FpsRecorder.kt           # Choreographer 逐帧统计 + 记录会话状态
│   └── PowerRecorder.kt         # 耗电录制：逐秒累积、应用侧加权、功耗与续航计算
├── model/Models.kt              # 数据模型
├── vm/DeviceViewModel.kt        # 1 秒采样循环 + 累积趋势缓冲 + 全部控制入口
├── ui/
│   ├── OsPlusApp.kt             # 根布局：一级页路由 + 二级详情栈 + 液态玻璃底栏
│   ├── theme/                   # OsTokens（设计令牌）、Theme
│   ├── components/              # Surfaces / LiquidGlass / GlassShaders / Charts / Navigation / Common
│   └── screen/                  # Overview / Perf / Fps / Power / Settings / 详情页 / 性能调度
├── service/FpsOverlayService.kt # 跨应用监视前台服务：通知 / 悬浮窗 / 保活锚点
└── receiver/BootReceiver.kt

app/src/test/java/com/osplus/tools/
├── DownsampleTest.kt            # 趋势降采样
├── FpsRecordTest.kt             # 帧率记录与 CSV 行格式
└── GpuDataSourceTest.kt         # GPU 频率单位判定
```

约 **13200 行 Kotlin**。

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

真机核查：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
# 实时任务通知是否被提升为置顶卡片
adb shell dumpsys notification --noredact | grep -E "PROMOTED|shortCritical"
# 进程是否被系统冻结（1 = 已冻结）
adb shell "cat /sys/fs/cgroup/apps/uid_<uid>/pid_<pid>/cgroup.freeze"
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
   （上限 7200 条 ≈ 2 小时）并导出 CSV。
6. **ZRAM 容量调整会重建交换分区**，正在使用交换区的应用可能出现短暂卡顿。
   需先开启「允许调整」闸门，默认关闭；部分机型内核不允许运行时改小 zram。
7. **性能调度页依赖第三方模块**。Uperf Game Turbo 与 A-SOUL Games Optimization
   均为独立 Magisk 模块，未安装时对应卡片只显示「未检测到模块」。
8. **单核调频在物理上不可实现**。本机 `cpu0..5` 的 `scaling_max_freq` 是同一 inode，
   写任意一个都影响整组，且 `policy0` 的写入被内核静默忽略——界面会明确列出
   调频策略组影响范围并做回读校验，而不是做一个看起来能单核调节的假开关。

---

## 七、相关文档

| 文档 | 内容 |
|---|---|
| [`docs/README.md`](docs/README.md) | 液态玻璃悬浮导航栏的完整实现（两层 backdrop、AGSL 折射 SDF 位移场）；实时任务通知的可配置化、通道迁移、后台冻结约束与保活锚点的实测记录 |
| `dist/README.md` | 另一条独立交付线：把 Uperf / A-SOUL 两个 Magisk 模块打包成自带 WebUI 的可刷模块 |
