# OSPlus · Android 性能监视工具

包名 `com.osplus.tools` ｜ 版本 1.0.0 ｜ minSdk 33 (Android 13) ｜ targetSdk 36

一个完全自研的本地性能监视与调优工具。所有数据来自 `/proc`、`/sys` 与系统 API，
**不做任何云端上报**；涉及内核写入的功能全部经 root 执行。

---

## 一、功能清单

| 模块 | 能力 | 数据来源 |
|---|---|---|
| **概览** | CPU 总占用、内存、GPU 频率、整机功耗、实时帧率、电池温度的 **5 秒实时柱状图**；每核占用柱状图；设备概况 | 见下 |
| **性能 · CPU** | 核心簇频率与量程、调速器切换、**按核心选择目标后以「挡位」方式锁定频率（含调频策略组影响范围提示与写入回读校验）**、每核占用实时柱状图 | `cpufreq` sysfs |
| **性能 · GPU** | GPU 型号 / 当前频率 / 负载 / 调速器 / 可用频率表；**调速器切换**；频率上限调节 | `kgsl-3d0` sysfs |
| **性能 · 内存** | 内存占用与 SWAP 5 秒柱状图；内存明细；SWAP / ZRAM 容量与压缩比；swappiness 调节；**ZRAM 容量调整（重建交换分区）** | `/proc/meminfo`、`/proc/swaps`、`/sys/block/zram*` |
| **电源 · 耗电统计** | 近 24 小时各应用**图标**、前台/可见时长与估算耗电占比 | `UsageStatsManager` |
| **电源 · 充电统计** | 功耗与电池温度 5 秒柱状图；电压/电流/功率/容量/循环次数 | `power_supply` sysfs |
| **电源 · 充电控制** | 充电开关、充电电流上限；内核可用节点探测 | `charging_enabled` / `input_suspend` / `constant_charge_current_max` |
| **进程 · 进程管理** | 全量进程列表（**应用图标** / CPU% / 常驻内存 / 用户 / 状态），按 CPU 或内存排序，强制停止与结束进程 | root `top -b -n 1` |
| **进程 · 帧率记录** | 基于 Choreographer 的实时 FPS、平均/最大帧耗时、卡顿计数；**30 秒 / 1 / 5 / 10 分钟 / 全部 多档分析窗口**；**折线趋势 / 柱状对比可切换**；**录制期间每秒同步留档每核占用与频率、GPU、内存、功耗、温度**；**一键导出 CSV**；**跨应用帧率悬浮窗**；系统 `dumpsys gfxinfo` 明细 | 系统 API |
| **设置** | **主题切换（跟随系统 / 浅色 / 深色）与壁纸取色（Monet）开关**；Root 状态与 `su` 路径、使用情况访问、悬浮窗、通知权限；采样开关与间隔；设备与内核信息 | — |
| **交互** | 顶栏右上角一键**暂停/继续实时采样**；预测性返回手势：非「概览」页按返回带系统预测动画回到概览，概览页按返回正常退出应用 | — |

### 实时采样规格

- 采样间隔 **1 秒**，柱状图窗口 **5 秒（5 根柱）**
- 每根柱 = 该秒的采样值；CPU 占用率由 `/proc/stat` 相邻两次采样求差得到
- 每核柱状图：**有多少核心画多少根柱**，柱高 = 占用率，柱顶 = 当前频率，柱下 = 核心编号
- 颜色随占用率递进：绿 → 蓝 → 橙 → 红

---

## 二、关键技术决策

### 1. Android 12+ 的节点读取限制（本项目最核心的适配点）

实测设备（OnePlus / SM8750 / Android 16）上，以下节点的 SELinux 标签**不允许普通应用读取**：

| 节点 | 标签 | 影响 |
|---|---|---|
| `/proc/stat` | `proc_stat` | CPU 占用率恒为 0 |
| `/proc/swaps` | `proc_swaps` | SWAP 信息为空 |
| `/proc/uptime` | `proc_uptime` | 开机时长不可读 |
| `/sys/class/power_supply/battery/*` | `vendor_sysfs_battery_supply` | 电流/温度不可读 |
| `/sys/class/kgsl/kgsl-3d0/*` | `vendor_sysfs_kgsl` | GPU 频率/负载不可读 |

因此 `SystemProbe` 把一秒内需要的全部节点**合并为一次 root 命令**执行，
将开销压到最低；CPU 频率等可直读节点仍走直接文件读取，避免不必要的 root 调用。

### 2. 功耗计算

该机型 `current_now` 单位为 mA 且空闲时恒为 0，直接换算会得到 0 mW。
改用 `charge_counter`（µAh）做 **6 秒滑动窗口求导**：

```
电流(µA) = |Δcharge_counter(µAh)| × 3600 / Δt(s)
功耗(mW) = 电压(mV) × 电流(µA) / 1e6
```

窗口未成熟时沿用上次结果，避免曲线在 0 与真值之间跳变；若 `charge_counter`
不可用则回落到 `current_now`（按数量级自动判定 mA / µA）。

### 3. 节点读取必须无条件回退到 root

`Shell.readNode` 早期实现为「直接读失败且文件存在 → 返回 null」，
本意是避免为不存在的节点白跑一次 shell。但 Android 12+ 的厂商 sysfs 节点
**文件存在却因 SELinux 被拒绝**，这个分支会直接吞掉结果、永不提权，
导致 GPU 频率/调速器、电池节点全部读不到。现改为：直接读失败一律交给 shell 重试一次
（节点不存在时 shell 也只会返回空串，代价可忽略）。

### 4. 频率单位必须按数量级判定，不能靠文件名

厂商 sysfs 目录被 SELinux 拒绝列举时 `File.exists()` 返回 false，
据此判断「该节点是 MHz 还是 Hz」会误判，把 222 MHz 当成 222 Hz。
现统一按数量级判定：GPU 频率若以 Hz 表示必然 ≥ 1e8，MHz 表示则落在 100~3000。
同时修复了 GPU 详情误用 CPU 的 kHz 格式化函数导致的**二次除以 1000**
（222 MHz 显示为 0 MHz）。

### 5. 单核调频的物理限制（重要）

本机实测（SM8750 / Adreno 830v2）：

| 接口 | 结果 |
|---|---|
| `cpu0..cpu5/cpufreq/scaling_max_freq` | **同一 inode**（均指向 `policy0`），写入任意一个都会影响整组 |
| `cpu6..cpu7/cpufreq/scaling_max_freq` | 同一 inode（`policy6`） |
| `cpuN/online` | 存在但写入被拒（`Permission denied`，内核未开放单核热插拔） |
| `cpu0/core_ctl/*`（高通核心控制） | 存在但写入被拒，且 `enable=0` |
| `cpuN/cpufreq/` 下的 `scaling_*` | 无独立单核节点 |

**结论：该 SoC 不存在可写的单核频率节点，单核独立调频在物理上无法实现。**

因此「核心频率控制」的实现方式是：允许选择任意核心作为目标，实时显示该核心的频率/占用/
硬件范围，并**明确列出其所属调频策略组**（如「核心 0,1,2,3,4,5（共 6 核）」），
在界面上说明调节会作用于整组——而不是做一个看起来能单核调节、实际无效的假开关。

### 5.1 两个簇的可控性不同（实测）

| 簇 | 频率写入 | 实测结果 |
|---|---|---|
| `policy6`（核心 6,7 大核） | **可写且生效** | 锁定 1689 MHz → 内核回读 `min=max=cur=1689600`，CPU 实际运行在该挡位 |
| `policy0`（核心 0-5 小核） | **被静默忽略** | 请求 2745 MHz → 回读仍为 960000；改 governor 后仍拒绝，被内核/厂商策略接管 |

**因此频率控制改用「挡位」而非上下限滑块**：直接把 min 与 max 同时设为所选挡位，
CPU 便稳定运行在该频率（这是让 CPU 真正跑在指定频率的标准做法，
仅设上限只是设置天花板，governor 仍会向下浮动）。

**并加入写入回读校验**：写入后立刻回读 `scaling_min/max_freq` 并与请求值比对，
不一致时在界面上明确提示「未生效：请求 X MHz，但内核回读仍为 Y MHz，
该簇频率被内核或厂商策略接管」——避免出现「拖了滑块却什么都没发生」的静默失败。

### 5.2 同样的教训推广到所有控制路径（实测）

| 控制项 | 实测结果 | 处理 |
|---|---|---|
| CPU 大核簇（6,7）频率锁定 | **生效** | 回读校验，UI 提示已生效 |
| CPU 小核簇（0-5）频率 | **静默忽略** | 回读校验 + UI 明确报「未生效」 |
| GPU 频率上限 | **生效** | 回读校验 |
| GPU 调速器 | **静默忽略**（本机 `available_governors` 只有 msm-adreno-tz 一项） | 选项改为**从设备读取**，不再硬编码；写入带回读 |
| swappiness | **生效**（100→60→100 往返验证） | 写入带回读 |

GPU 调速器的教训尤其值得记：最初硬编码了 4 个常见调速器，
本机内核只支持 1 个——**另外 3 个按钮是点了必然失败的假选项**。
调速器列表必须来自 `available_governors`，正如 CPU 频率挡位必须来自
`scaling_available_frequencies`。

### 5.2 同样的教训推广到所有控制路径（实测）

| 控制项 | 实测结果 | 处理 |
|---|---|---|
| CPU 大核簇（6,7）频率锁定 | **生效** | 回读校验，UI 提示已生效 |
| CPU 小核簇（0-5）频率 | **静默忽略** | 回读校验 + UI 明确报「未生效」 |
| GPU 频率上限 | **生效** | 回读校验 |
| GPU 调速器 | **静默忽略**（本机 `available_governors` 只有 msm-adreno-tz 一项） | 选项改为**从设备读取**，不再硬编码；写入带回读 |
| swappiness | **生效**（100→60→100 往返验证） | 写入带回读 |

GPU 调速器的教训尤其值得记：最初硬编码了 4 个常见调速器，
本机内核只支持 1 个——**另外 3 个按钮是点了必然失败的假选项**。
调速器列表必须来自 `available_governors`，正如 CPU 频率挡位必须来自
`scaling_available_frequencies`。

### 6. 录制不能绑定在页面生命周期上

帧率页最初在 `DisposableEffect.onDispose` 里停止录制，本意是「离开应用就停」，
但 **Compose 的 onDispose 在切换标签页时同样会触发**，结果变成
「一切到别的页面，记录就断了」。

录制状态应只由用户显式开关控制，因此：
- 录制状态与数据都放在 `ViewModel`（跨标签页存活），页面只读不写
- 页面 `onDispose` 不再做任何停止动作

跨应用记录依赖前台服务保活（否则进程进入 cached 状态会被系统冻结）：
- `FpsOverlayService` 用共享的 `FpsRecorder` 作为采样源（悬浮窗是本进程的可见窗口，
  系统会持续投递 vsync），因此切到其他应用后帧率仍在采集
- 未开启悬浮窗时界面会提示「切到其他应用后可能停止采集」

**悬浮窗开关绑定服务的真实运行状态**（`FpsOverlayState`），而不是偏好设置里的值：
应用被强停后服务已死，若开关仍读偏好就会显示成「开启」，
用户一点反而把本就没运行的服务「关掉」。服务在 `onCreate/onDestroy` 写入真实状态，
界面订阅它；应用启动时若偏好为开则重新拉起服务，使状态与实际一致。

### 7. 进程列表

应用无法读取其他进程的 `/proc/<pid>`。改用 root 执行一次：

```
top -b -n 1 -q -o %CPU,RES,PID,USER,S,ARGS
```

一次调用即可获得全部 ~1000 个进程的 CPU 占用、常驻内存与名称。
由 `USER` 列（`u{userId}_a{appId}`）反推 uid，再经 `PackageManager` 映射到包名。
采集用的 `top` / `ps` 辅助进程已从列表中过滤。

### 8. UI 设计

- **Miuix 0.9.4**（HyperOS 风格组件库）作为基础组件层
- 顶栏：通栏**真实背景模糊**（`miuix-blur` 的 `drawBackdrop` + `LayerBackdrop`，模糊半径 30dp）
  + 半透明着色层 + 顶光渐变 + 发丝分隔线
- 底栏：Apple 风格悬浮胶囊，同样接入真实背景模糊；选中项展开为「图标 + 文字」高亮胶囊，
  带高光描边与投影
- 模糊链路：内容层用 `Modifier.layerBackdrop(backdrop)` 录制进 `GraphicsLayer`，
  顶/底栏用 `Modifier.drawBackdrop(backdrop, shape = { … }, effects = { blur(px) })` 取用；
  `RuntimeShader` 在 API 33+ 可用（本应用 minSdk 33），低版本自动降级为纯半透明
- 图表：全部使用 `Canvas` 手绘——圆角柱、柱底轨道、顶部渐变、虚线网格、基线、柱顶数值标签
- 分段控件为自绘，替代 Miuix `TabRow`，与整体视觉语言统一
- 页面背景为柔和竖向渐变 + 品牌色径向晕染，使玻璃层有内容可透
- 主题跟随系统深浅色，**不使用 Monet 壁纸取色**（避免发灰、对比度不足）

### 9. 其他

- 预测性返回手势：`android:enableOnBackInvokedCallback="true"` + `enableEdgeToEdge()`，
  并用 `androidx.navigationevent` 的 `NavigationBackHandler` + `rememberNavigationEventState`
  接管返回逻辑（非概览页 → 回概览；概览页不拦截，交还系统执行退出动画）
- 开关状态持久化（`SharedPreferences`）：帧率悬浮窗与实时采样开关在重启后自动恢复；
  `BootReceiver` 在开机后按偏好恢复悬浮窗前台服务
- 悬浮窗帧率：`specialUse` 前台服务 + `TYPE_APPLICATION_OVERLAY`
- **不使用 LSPosed/Xposed**：全部功能在自身进程内通过 Root 完成，
  没有需要注入其他进程的场景；若声明 `xposedmodule=true` 而不提供 hook 实现，
  LSPosed 会把它列为可激活模块，激活后毫无作用，对用户是误导，故不声明
- 所有采集在 `Dispatchers.IO` 执行，UI 仅订阅 `StateFlow`，不阻塞主线程

---

## 三、工程结构

```
app/src/main/java/com/osplus/tools/
├── MainActivity.kt              # 唯一 Activity，edge-to-edge + 预测性返回
├── OsPlusApplication.kt         # 通知渠道
├── core/
│   ├── Shell.kt                 # su/sh 执行封装（root 探测缓存、节点读写）
│   ├── SystemProbe.kt           # 一次 root 命令采集全部受限节点
│   ├── CpuDataSource.kt         # CPU 信息、簇识别、增量占用率、频率与调速器控制
│   ├── GpuDataSource.kt         # kgsl / devfreq 多路径探测与频率控制
│   ├── MemDataSource.kt         # 内存、SWAP、ZRAM、swappiness
│   ├── BatteryDataSource.kt     # 电池信息 + ChargeController 充电控制
│   ├── ProcessDataSource.kt     # root top 全量进程列表
│   ├── PowerStatsDataSource.kt  # UsageStats 耗电估算
│   └── FpsRecorder.kt           # Choreographer 逐帧统计
├── model/Models.kt              # 数据模型（含 MetricSample 采样点）
├── vm/DeviceViewModel.kt        # 1 秒采样循环 + 5 秒窗口 + 全部控制入口
├── ui/
│   ├── OsPlusApp.kt             # 根布局：顶栏 + 内容 + 悬浮底栏
│   ├── theme/Theme.kt
│   ├── components/              # Glass / Charts / Bars / Common
│   └── screen/                  # Dashboard / Perf / Power / Process / Settings
├── service/FpsOverlayService.kt # 跨应用帧率悬浮窗
└── receiver/BootReceiver.kt
```

---

## 四、构建

环境：JDK 17、Android SDK（platform 37.2 + build-tools 37.0.0）、Gradle 9.7.1、AGP 9.4.1、Kotlin 2.4.20

```bash
cd C:/Work/OSPlus
export JAVA_HOME="C:/Program Files/Amazon Corretto/jdk17.0.20_10"
/c/Work/gradle/gradle-9.7.1/bin/gradle --no-daemon assembleDebug     # 调试包
/c/Work/gradle/gradle-9.7.1/bin/gradle --no-daemon assembleRelease   # 精简包
```

> AGP 9 起内置 Kotlin 支持，`org.jetbrains.kotlin.android` 插件必须移除，
> 仅保留 `org.jetbrains.kotlin.plugin.compose`。

---

## 五、已知限制

1. **需要 root 才能完整工作**。无 root 时仅内存与 CPU 频率可读，
   CPU 占用率、GPU、功耗、进程列表、所有控制功能均不可用（界面会给出提示）。
2. **充电控制依赖内核节点**。不同机型暴露的节点名不同，应用会自动探测；
   未暴露则开关置灰并在「内核节点探测」中列出实际可用项。
3. **耗电统计为估算值**。Android 未开放按应用的真实耗电量接口，
   此处以 UsageStats 前台/可见时长加权计算占比，非硬件实测功耗。
4. **背景模糊依赖 `RuntimeShader`**（AGSL），需要 Android 13 (API 33) 及以上。
   API 33 以下会自动降级为纯半透明玻璃，不会崩溃但观感变弱。
   全屏内容每帧录制进 `GraphicsLayer` 会带来少量 GPU 开销，
   在低端设备上可考虑将模糊半径调小或改用非模糊分支。
5. **帧率记录**：录制期间每秒留档一条（上限 7200 条 ≈ 2 小时），CSV 通过 MediaStore 写入
   「下载 / OSPlus /」，无需存储权限。录制不随页面切换中断；**跨应用记录需开启悬浮窗**
   （前台服务保活，否则进程被冻结后停止采集）；跨应用显示需在「进程 · 帧率记录」中打开
   「悬浮窗显示」开关，并授予悬浮窗权限。Android 13+ 还需通知权限，否则前台服务通知不可见
   （首次启动会自动申请）。
6. **ZRAM 容量调整会重建交换分区**（`reset` → `disksize` → `mkswap` → `swapon`），
   正在使用交换区的应用可能出现短暂卡顿。界面已给出明确警示，但仍建议在设备空闲时操作；
   部分机型内核不允许运行时改小 zram，此时写入会失败且不产生副作用。
