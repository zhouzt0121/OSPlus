# OSPlus · Android 性能监视与调优工具

包名 `com.osplus.tools` ｜ 版本 **2.1.0**（versionCode 15）｜ minSdk 33 (Android 13) ｜ targetSdk 36 ｜ compileSdk 37

一个完全自研的**本地**性能监视与调优工具。所有数据来自 `/proc`、`/sys` 与系统 API，
**不做任何云端上报**；涉及内核写入的功能全部经 root 执行。

> 本文件是项目总览与设计决策索引。**液态玻璃导航栏**与**实时任务通知**两块的完整实现细节
> （含大量真机实测参数与踩坑）另见 [`docs/README.md`](docs/README.md)。

---

## 一、功能清单

### 信息架构

**统一顶栏**（左侧标题 + 右侧页面动作）承载页面身份，底部悬浮导航分
**概览 / 性能 / 帧率 / 电源** 四个一级页（纯图标，不显示文字）；
**设置移出导航栏**，改挂顶栏右侧动作区。概览与性能页的卡片可下钻到
**内存 / GPU / CPU / 进程 / 性能调度** 五个二级详情页，二级页在顶栏左侧显示返回按钮。

### 页面

| 页面 | 能力 | 数据来源 |
|---|---|---|
| **概览**（一级） | **健康结论条**（按权限 / SoC 结温 / 电池温度 / 内存四项判据给出一句「算不算正常」）；**2×2 指标网格**（CPU / GPU / 内存用**圆环**表达水位，名称、百分比、说明行**全部收在环心**；第四格为**帧率折线卡**）；设备概况（含 **GPU 型号**）。四个动作（**清理内存 / 清理交换 / 记录帧率 / 设置**）全部收在**顶栏右侧**，滚到任何位置都够得着 | 见下 |
| **性能**（一级） | **进程摘要置顶**（图标 / 名称 / CPU%，点卡片进进程管理）；**5 秒 / 1 分 / 5 分 / 30 分 时间窗分段控件**；CPU 总占用、内存占用、GPU 频率、整机功耗、实时帧率、电池温度六条**折线趋势**；每核占用与频率柱状图；**调优入口**（CPU 调速器 / GPU 调速器 / ZRAM / 性能调度，右侧显示当前状态摘要） | `/proc`、`/sys` |
| **帧率**（一级） | 基于 Choreographer 的实时 FPS、平均/最大帧耗时、卡顿计数；**30 秒 / 1 / 5 / 10 分钟 / 全部 多档分析窗口**；全部为**折线趋势**；**录制期间每秒同步留档每核占用与频率、GPU、内存、功耗、温度**，并实时显示递增的**记录时长**；**一键导出 CSV**；**跨应用实时监视**开关（呈现方式与显示项见「设置」）；系统 `dumpsys gfxinfo` 明细 | 系统 API |
| **电源**（一级） | **电池态势卡**（环图 + 剩余可用时长估算 + 电压 / 温度 / 电流 / 供电状态）；**耗电统计（录制式）**：手动开始 / 结束一次录制，每秒采集电量、电压、温度、电流，绘制**真实时间轴**的电量曲线（X 轴 0~10 分钟自动扩展，Y 轴 0/25/50/75/100%），自动计算平均功耗与理论续航，并按应用拆分耗电明细（曲线图 / 列表双视图；顶栏可复制 / 删除本次记录）；**充电统计**：功耗与电池温度折线、电压/电流/功率/容量/循环次数；**充电控制**：充电开关、充电电流上限 | `BatteryManager`、`UsageStatsManager`、`power_supply` sysfs |
| **设置**（二级，顶栏右侧齿轮） | **主题切换（跟随系统 / 浅色 / 深色）与壁纸取色（Monet）开关**；**实时采样**开关与间隔；**实时任务通知**（呈现方式开关 + 9 项显示配置 + 后台保活锚点）；Root 状态与 `su` 路径、使用情况访问、悬浮窗、通知权限；设备与内核信息 | — |
| **内存详情** | 占用率与 SWAP 累积曲线；内存明细；**ZRAM 容量调整（需先开启「允许调整」闸门；范围 0~8 GB，0 表示关闭交换分区）**；swappiness 调节 | `/proc/meminfo`、`/proc/swaps`、`/sys/block/zram*` |
| **GPU 详情** | 频率/负载累积曲线；型号 / 当前频率 / 调速器 / 可用频率表；**调速器切换**；频率上限调节 | `kgsl-3d0` sysfs |
| **CPU 详情** | 总占用与每核占用柱状图；核心簇频率与量程；**调速器切换**；**按核心选择目标后以「挡位」方式锁定频率（含调频策略组影响范围提示与写入回读校验）** | `cpufreq` sysfs |
| **进程详情** | 全量进程列表（**应用图标** / CPU% / 常驻内存 / 用户 / 状态），按 CPU 或内存排序，强制停止与结束进程 | root `top -b -n 1` |
| **性能调度**（二级） | 接管 **Uperf Game Turbo** 与 **A-SOUL Games Optimization** 两个模块：Uperf 电源档位切换（省电 / 均衡 / 性能 / 极速 / 自动 / 疯狂）、分应用模式规则增删改（含 `*` 默认与 `-` 息屏两条特殊规则）、A-SOUL 运行模式（硬亲和 / 软迁移 / 硬迁移）与实时模式、分游戏亲和覆盖、两个守护进程的重启。规则以**卡内展开**方式编辑，应用选择器带搜索 | 模块自身的配置文件 |
| **交互** | **统一顶栏**（各页高度 / 字号 / 按钮尺寸完全一致）：左侧标题、二级页多一个返回按钮、右侧为页面动作区；底部导航为**液态玻璃**材质、纯图标（无文字），点选 + 横向拖动切换一级页；预测性返回手势：二级路由返回回到所属一级页，一级页返回回到概览，概览页按返回正常退出应用 | — |

### 实时采样规格

- 采样间隔 **1 秒**；趋势数据**累积保留，上限 1800 条（30 分钟）**，超出后按滚动窗口丢弃最早一条
- 趋势窗口由性能页顶部的**分段控件**选择（**5 秒 / 1 分 / 5 分 / 30 分**，默认 5 分）：
  短窗口看瞬时抖动，长窗口看走势。窗口长度只影响**绘制范围**，不改变采样与留存策略
- 绘制时统一降采样到 **60 个槽位**，保证曲线密度稳定、长时间运行也不掉帧；
  时间轴左端显示相对当前的偏移（`-mm:ss`）
- CPU 占用率由 `/proc/stat` 相邻两次采样求差得到
- 每核柱状图：**有多少核心画多少根柱**，柱高 = 占用率，柱顶 = 当前频率，柱下 = 核心编号
- 颜色随占用率递进：绿 → 蓝 → 橙 → 红
- **三处实时读数（抽屉置顶卡片 / 状态栏芯片 / 悬浮窗）统一按 1 秒刷新**，与采样间隔同频

---

## 二、关键技术决策

### 1. Android 12+ 的节点读取限制（最核心的适配点）

实测设备（OnePlus PJZ110 / SM8750 / Android 17）上，以下节点的 SELinux 标签
**不允许普通应用读取**：

| 节点 | 标签 | 影响 |
|---|---|---|
| `/proc/stat` | `proc_stat` | CPU 占用率恒为 0 |
| `/proc/swaps` | `proc_swaps` | SWAP 信息为空 |
| `/proc/uptime` | `proc_uptime` | 开机时长不可读 |
| `/sys/class/power_supply/battery/*` | `vendor_sysfs_battery_supply` | 电流/温度不可读 |
| `/sys/class/kgsl/kgsl-3d0/*` | `vendor_sysfs_kgsl` | GPU 频率/负载不可读 |

因此 `SystemProbe` 把一秒内需要的全部节点**合并为一次 root 命令**执行
（用 `@@OSPLUS@@` 分段），将开销压到最低；CPU 频率等可直读节点仍走直接文件读取，
避免不必要的 root 调用。

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

**同一个坑在 `BatteryDataSource` 里又踩了一次**（一直存在，直到耗电录制页才暴露）：
它用 `File("$dir/$name").exists()` 判断节点是否存在，而 SELinux 拒绝会让厂商 sysfs
目录**列举失败、`exists()` 返回 false**，于是 `charge_full_design` 等节点被整个跳过。
表现是「电池能量显示 0.0 Wh」，全程不报任何错。

修法是把逐节点读改成**一次 root 合并命令**（`for d in ...; do [ -f $d/$name ] && cat ...`），
用 root shell 内部的 `[ -f ]` 代替 `exists()`：既绕开 SELinux，又只需一次 `su`
（8 个逻辑节点 × 3 个候选目录若逐次调用就是 24 次 `su`，每 5 秒一轮，开销不可接受）。
`ChargeController` 的节点探测同理——否则充电控制在被 SELinux 保护的机型上会整体失效，
而界面上只表现为「未检测到可用节点」，看不出是权限问题。

### 4. 频率单位必须按数量级判定，不能靠文件名

厂商 sysfs 目录被 SELinux 拒绝列举时 `File.exists()` 返回 false，
据此判断「该节点是 MHz 还是 Hz」会误判，把 222 MHz 当成 222 Hz。
现统一按数量级判定：GPU 频率若以 Hz 表示必然 ≥ 1e8，MHz 表示则落在 100~3000。
同时修复了 GPU 详情误用 CPU 的 kHz 格式化函数导致的**二次除以 1000**（222 MHz 显示为 0 MHz）。

### 5. 单核调频的物理限制

本机实测（SM8750 / Adreno 830v2）：

| 接口 | 结果 |
|---|---|
| `cpu0..cpu5/cpufreq/scaling_max_freq` | **同一 inode**（均指向 `policy0`），写入任意一个都会影响整组 |
| `cpu6..cpu7/cpufreq/scaling_max_freq` | 同一 inode（`policy6`） |
| `cpuN/online` | 存在但写入被拒（内核未开放单核热插拔） |
| `cpu0/core_ctl/*`（高通核心控制） | 存在但写入被拒，且 `enable=0` |
| `cpuN/cpufreq/` 下的 `scaling_*` | 无独立单核节点 |

**结论：该 SoC 不存在可写的单核频率节点，单核独立调频在物理上无法实现。**

因此「核心频率控制」的实现方式是：允许选择任意核心作为目标，实时显示该核心的
频率/占用/硬件范围，并**明确列出其所属调频策略组**（如「核心 0,1,2,3,4,5（共 6 核）」），
在界面上说明调节会作用于整组——而不是做一个看起来能单核调节、实际无效的假开关。

#### 5.1 两个簇的可控性不同

| 簇 | 频率写入 | 实测结果 |
|---|---|---|
| `policy6`（核心 6,7 大核） | **可写且生效** | 锁定 1689 MHz → 内核回读 `min=max=cur=1689600` |
| `policy0`（核心 0-5 小核） | **被静默忽略** | 请求 2745 MHz → 回读仍为 960000 |

**因此频率控制改用「挡位」而非上下限滑块**：直接把 min 与 max 同时设为所选挡位，
CPU 便稳定运行在该频率（仅设上限只是设置天花板，governor 仍会向下浮动）。
**并加入写入回读校验**，不一致时明确提示「未生效：请求 X MHz，内核回读仍为 Y MHz」，
避免出现「拖了滑块却什么都没发生」的静默失败。

#### 5.2 同样的教训推广到所有控制路径

| 控制项 | 实测结果 | 处理 |
|---|---|---|
| CPU 大核簇频率锁定 | **生效** | 回读校验，UI 提示已生效 |
| CPU 小核簇频率 | **静默忽略** | 回读校验 + UI 明确报「未生效」 |
| GPU 频率上限 | **生效** | 回读校验 |
| GPU 调速器 | **静默忽略**（本机 `available_governors` 只有 `msm-adreno-tz` 一项） | 选项改为**从设备读取**；写入带回读 |
| swappiness | **生效**（100→60→100 往返验证） | 写入带回读 |

GPU 调速器的教训尤其值得记：最初硬编码了 4 个常见调速器，本机内核只支持 1 个——
**另外 3 个按钮是点了必然失败的假选项**。调速器列表必须来自 `available_governors`，
正如 CPU 频率挡位必须来自 `scaling_available_frequencies`。

调速器与核心选择的**选中态用整块变色表达（底色 + 描边 + 文字色），不在文案前打勾**：
打勾会改变文案宽度，同一行的胶囊在选中/取消时宽度跳动，行宽不稳定。

### 6. 录制不能绑定在页面生命周期上

帧率页最初在 `DisposableEffect.onDispose` 里停止录制，本意是「离开应用就停」，
但 **Compose 的 onDispose 在切换标签页时同样会触发**，结果变成
「一切到别的页面，记录就断了」。因此：

- 录制状态与数据都放在 `ViewModel`（跨标签页存活），页面只读不写
- 页面 `onDispose` 不再做任何停止动作
- **记录会话状态进一步下沉到 `FpsRecorder` 单例**：桌面悬浮窗轻点也能开关记录，
  而悬浮窗在应用退到后台后依然存在，若状态只存在 ViewModel 里，
  就会出现「悬浮窗已开始记录、页面开关却显示关闭」的撕裂。两边读写同一个 `StateFlow`。
- 同理，`FpsRecorder` 用 `overlayHolds` 标志仲裁「谁还在用统计器」：
  结束记录时若悬浮窗仍在运行则保留逐帧回调（否则窗上数字会冻结），
  悬浮窗销毁时若仍在记录则不停统计器（否则记录被中断）。

**悬浮窗手势：轻点切换记录，长按或拖动移动位置。** 两种意图按「按住时长 + 位移」区分：
位移超过 8 px 立即进入拖动，按住超过 320 ms 也进入拖动，两者都不满足且按压很短才算轻点。
记录中窗上数值前会显示一个红点，通知文案也同步切换。

**悬浮窗开关绑定服务的真实运行状态**（`FpsOverlayState`），而不是偏好设置里的值：
应用被强停后服务已死，若开关仍读偏好就会显示成「开启」，用户一点反而把本就没运行的服务
「关掉」。服务在 `onCreate/onDestroy` 写入真实状态，界面订阅它。

#### 6.1 悬浮窗透明度必须改 View，不能改 Window（Android 12+ 触摸限制）

1.2.0 曾出现一个 bug：**悬浮窗不透明度调到 50% 以下后，轻点 / 长按 / 拖动全部失效**。

根因是 Android 12 引入的「**不受信任的触摸事件**」限制。对 `TYPE_APPLICATION_OVERLAY`
窗口，系统会看它的 `LayoutParams.alpha`：`alpha > InputManager.getMaximumObscuringOpacityForTouch()`
（当前 **0.8**）才是正常的触摸目标；`alpha <= 0.8` 时窗口被判定为「遮挡不足」，
**系统不再把触摸事件投递给它**，logcat 会打印
`Untrusted touch due to occlusion by com.osplus.tools`。

**修法：窗口 `alpha` 恒为 `1.0`，视觉透明度改由 `View.alpha` 承担。**

```kotlin
params.alpha = 1f          // 窗口在输入层面永远是「足够遮挡」的触摸目标
overlayView.alpha = value  // 透明度只影响绘制，不影响输入分发
```

这与官方文档「要让触摸**穿透**下去必须在窗口级别降低不透明度」并不矛盾——
那是「主动放弃接收触摸」，需求相反。该阈值是**窗口**层面的，
`View.alpha` 无论调到多低都不会触发，因此修复后 25% 的不透明度依然可点可拖。

### 7. 实时任务通知（2.0.0 主线）

#### 7.1 两种呈现方式，一个开关

「设置 → 实时任务通知」的开关决定跨应用实时数据**怎么呈现**，两种方式互斥：

| 开关 | 呈现方式 | 依赖 |
|---|---|---|
| **开**（默认） | 系统实时任务通知：通知抽屉置顶卡片 + 状态栏芯片常驻 | 无 |
| **关** | `TYPE_APPLICATION_OVERLAY` 悬浮窗：轻点切换记录、长按/拖动移动位置 | `SYSTEM_ALERT_WINDOW` |

开关与帧率页的「跨应用实时监视」（控制前台服务是否运行）是两个**正交**维度：
后者决定**采不采**，前者决定**怎么显示**。切换呈现方式时会顺带拉起服务，
否则用户改完开关看不到任何变化。

#### 7.2 显示项：9 选 3，且**上限来自平台**

通知 / 悬浮窗显示的指标项由用户勾选，**最多 3 项、至少 1 项**。上限是**平台硬限制**：
官方指南写明「支持衡量最多 3 项指标」，超出的项被系统**静默丢弃**（不报错），
只表现为「勾了却没显示」。因此选择侧就收敛到 3 项，构建处再 `take(3)` 兜一次。

| 项 | 值类型与单位 | 默认 |
|---|---|---|
| 帧率 | `FixedFloat` · FPS | **开** |
| CPU 占用 | `FixedInt` · % | **开** |
| GPU 占用 | `FixedInt` · % | **开** |
| CPU 频率 / GPU 频率 | `FixedText` · GHz/MHz | 关 |
| 内存占用 | `FixedInt` · % | 关 |
| 实时功耗 | `FixedText` · mW | 关 |
| 电池温度 | `FixedFloat` · ℃ | 关 |
| 充电速度 | `FixedText` · W | 关 |

**默认值依据**是「瞬时变化、且不依赖 root 就能读到」。排列顺序**固定按枚举声明顺序**，
与勾选先后无关。读不到的项一律显示 `--`，不显示 0——「读不到」与「就是 0」是两回事。

**标签必须短。** 系统 `MetricStyle` 把卡片宽度**均分**给每个指标，且字体与列宽由系统模板
决定、**应用无法调整字号**。展开态还会把单位拼到标签后（`内存占用` + `%` → `内存占用 (%)`），
超宽即被截断。因此 `NotifMetric` 把标签拆成 `label`（设置页完整名）与 `notifLabel`
（通知用短标签：`CPU` / `GPU` / `内存` / `功耗` / `温度` / `充电`）。

#### 7.3 `MetricStyle` 的引入级别是 API 37，不是 36

SDK 的 `api-versions.xml` 记为 `since="37.0"`，`Build.VERSION_CODES.CINNAMON_BUN = 37`。
早期代码用 `BAKLAVA`(36) 门控，在 **Android 16 上会引用不存在的类**
（`NoClassDefFoundError`，直接崩前台服务）。现已改为 `CINNAMON_BUN`，
并把整个提升分支包在 `runCatching` 里——任一步失败都退回 RemoteViews 卡片而不是崩溃。

相关：`setRequestPromotedOngoing` / `POST_PROMOTED_NOTIFICATIONS` 是 **36.1**，
`setShortCriticalText` 是 **36**，`SDK_INT` 无法区分 36.0 / 36.1，不要单独依赖它。

**官方明确 promoted 通知不得携带 `customContentView`（RemoteViews）**，否则直接失去资格。
因此两个分支**绝不能复用同一个 `Notification.Builder`**——若提升分支在 `build()` 抛异常前
已写入 `setStyle(MetricStyle)` / `setRequestPromotedOngoing(true)`，回退分支再挂 RemoteViews，
就会产出「既声明提升、又带自定义视图」的通知，被系统直接丢弃。
症状很有迷惑性：日志显示通知每秒都在发，但 `dumpsys` 里**没有这条 NotificationRecord**。
修法是抽出 `baseNotificationBuilder()`，两分支各 `new` 一个。

#### 7.4 通知通道的历史遗留与清理

通知通道**一旦创建就常驻**：应用不再注册、不再往它发通知，系统也不会删除它。
本应用因此攒下两条空壳（`fluid_cloud_task` 与 `osplus_fps`），其中一条与现用通道
**同名**「实时任务流体云」，让用户在类别列表里无法判断该关哪一条。
现每次启动主动 `deleteNotificationChannel` 清理。

注意是**软删除**：`dumpsys` 里仍能看到 `mDeleted=true` 的记录，但系统设置页会隐藏它们。
另外：**通道被关（`mImportance=0`）时，前台服务通知仍会被发布，但系统直接丢弃它**
（`dumpsys` 里查不到记录）——排查「通知没出现」时先看类别是否被关。

#### 7.5 三处显示统一 1 秒刷新

抽屉置顶卡片、状态栏芯片与悬浮窗共用**同一个 1 秒节拍**（`REFRESH_INTERVAL_MS`），
与采样间隔一致。**不能直接订阅数据流**：帧率来自 `FpsRecorder`（Choreographer 逐帧），
系统指标来自 `LiveMetrics`（每秒采样），是两条各自 1 秒的独立流，
`combine` 它们会在同一秒内触发两次重建。改用显式节拍后频率是确定的 1 Hz；
用户动作（切换呈现方式、点通知按钮切换记录）仍会立即刷新一次。

### 8. 后台保活：ColorOS 会冻结整个进程（重要架构约束）

**这是本项目最需要提前知道的平台约束。**

#### 8.1 现象与判定

应用退到后台约 5~10 秒后，系统用**内核 cgroup freezer** 冻结整个进程，
冻结期间进程得不到任何 CPU，采样与刷新协程全部停摆。同一时刻同步采样：

```
T+3s  CPU=565  cgroup.freeze=0   ← 仍在运行
T+6s  CPU=585  cgroup.freeze=1   ← 已被冻结
T+30s CPU=585  cgroup.freeze=1   ← 30 秒 CPU 增量 0
```

判定进程是否被冻结，三组数据放同一时刻读：

```bash
cat /sys/fs/cgroup/apps/uid_<uid>/pid_<pid>/cgroup.freeze   # 1 = 已冻结
cat /proc/<pid>/stat | awk '{print $14+$15}'                 # CPU 时间（间隔 10s 读两次）
grep voluntary_ctxt /proc/<pid>/status                       # 上下文切换（应同步增长）
```

#### 8.2 冻结来源是 ColorOS 自有框架，不是 AOSP

```
OplusHansManager : sp_FRZ uid=10551, pkg=com.sukisu.ultra, frozen by super freeze
OplusBinderProxy : proxyBinder uid: 10548 pkg: com.osplus.tools calling: OFreezer
Osense-BaseDecisionMaker: scene: SCENE_APP_SWITCH, excutingPolicy: freezer
```

binder 服务：`oplus_freeze: [com.oplus.app.IOplusHansFreezeManager]`。
因此 `dumpsys activity processes` 里该进程始终 `isFrozen=false`、`curProcState=4`
（FOREGROUND_SERVICE）——厂商走自己的速冻通道，AMS 字段不反映。

#### 8.3 逐一证伪的「保活」方案

| 思路 | 实测结果 |
|---|---|
| 前台服务（FGS） | **不足以豁免**厂商冻结 |
| 电池优化白名单 `dumpsys deviceidle whitelist +<pkg>` | **无效** |
| `settings put global cached_apps_freezer disabled` | **无效**（AOSP 旋钮管不到厂商框架） |
| root：冻结后跑命令自救 | **物理不成立**——冻结期间进程执行不了任何代码，包括 root 代码 |
| root：把采集搬到 root 守护进程 | 数据能保住，**通知保不住**（通知只能由应用进程发布） |
| root：持续调用 `oplus_freeze` 解冻 | 每次场景切换都会重新冻结，拉锯战 |

**结论：这不是权限问题，是进程状态问题。** root 给得了权限，
给不了「不被当作后台应用」的身份。

#### 8.4 解法：「保活锚点」——靠「可见」，不靠「权限」

冻结框架的目标是**后台应用**；持有可见窗口的进程不属于后台，不在目标集合里。
据此实现的方案是 **1×1 不可见悬浮窗（保活锚点）**：

```kotlin
WindowManager.LayoutParams(
    1, 1, TYPE_APPLICATION_OVERLAY,
    FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE or FLAG_NOT_TOUCH_MODAL,
    PixelFormat.TRANSLUCENT,
).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 0; alpha = 1f }
```

三条约束缺一不可：

1. **窗口 `alpha` 必须是 1.0**——0 会被判定为「不可见」，锚点就白挂了
   （与 6.1 同源，方向相反）；
2. **`FLAG_NOT_TOUCHABLE`**——绝不拦截任何触摸；
3. 需要 `SYSTEM_ALERT_WINDOW`，未授权时静默跳过、功能自动降级。

**实测（三次独立复现，每次 60 秒后台）**：

| 指标 | 无锚点 | **有锚点** |
|---|---|---|
| `cgroup.freeze` | T+6s 起恒为 1 | **全程 0** |
| 60 秒 CPU 增量 | **0** | 持续增长（696 → 1222，约 +9~12 tick/s） |
| 后台通知刷新 | 停止 | **97 条 / 60 秒**，稳定 1 秒一次 |
| 通知提升状态 | — | 保持 `PROMOTED_ONGOING` |

#### 8.5 覆盖不到的场景与代价

- **息屏后仍会被冻结**（屏幕关了，窗口不再「可见」）。这是平台行为，锚点覆盖不到。
  影响有限——息屏时没人看通知，**亮屏后进程立即解冻、通知马上恢复刷新**。
  若要息屏也保活，只剩持有 partial wake lock，代价是持续耗电。
- 通知模式若要后台保活，**仍然需要悬浮窗权限**——「免权限」与「后台持续刷新」
  在 ColorOS 上无法兼得，设置页把两个开关都给了用户。
- 锚点让进程常驻后台，实测约消耗 **10% 单核**，这是保活的必然成本。

### 9. `Shell` 的两条铁律

#### 9.1 必须有超时——否则 `su` 一挂住，整个监控永久停摆

真机抓到一条 `su -c <采样脚本>` 存活 **1 分 33 秒**，而 `run()` 内部是阻塞式 `waitFor()`，
于是采样循环永远停在原地、**不会自愈**。外部表现与「被冻结」几乎一样
（CPU 增量归零、通知不再刷新），但前台服务仍在、也没有崩溃日志，极难定位。

```kotlin
private const val COMMAND_TIMEOUT_MS = 5_000L
// 超时 → destroyForcibly() + 返回失败结果，最坏情况只是本轮读数缺失
```

`writeNode()` 里的 `Runtime.exec(su)` + `waitFor()` 同样加了超时。

#### 9.2 stdout / stderr 必须并发读，且读流体自己吞异常

**并发读**：串行的「先把 stdout 读到 EOF、再读 stderr」会在另一个管道被写满
（约 64 KB）时互相死锁——父进程等 stdout 关闭，子进程等 stderr 被读走。

**读流体必须自己吞掉异常**。超时分支 `destroyForcibly()` 关掉管道后，
挂起的 `readText()` 抛 IOException；它作为 **`async` 子协程失败会沿 Job 层级取消父协程、
绕过外层的 `runCatching`**，最终冒到采样循环之外——真机复现过一次
`APP CRASH(EXCEPTION)`，堆栈停在 `Shell$run$2$1$stdout$1.invokeSuspend`。

```kotlin
val stdout = async(Dispatchers.IO) {
    runCatching { process.inputStream.bufferedReader().readText() }.getOrDefault("")
}
```

**规则：`async` / `launch` 的异常不受外层 `runCatching` 保护**——
要么让子协程永不失败，要么用 `supervisorScope`。
另外采样循环外层也包了 `runCatching` + 日志：单次采样失败绝不该终结整个循环。

### 10. 进程列表

应用无法读取其他进程的 `/proc/<pid>`。改用 root 执行一次：

```
top -b -n 1 -q -o %CPU,RES,PID,USER,S,ARGS
```

一次调用即可获得全部 ~1000 个进程的 CPU 占用、常驻内存与名称。
由 `USER` 列（`u{userId}_a{appId}`）反推 uid，再经 `PackageManager` 映射到包名。
采集用的 `top` / `ps` 辅助进程已从列表中过滤。

### 11. 内存清理

概览页「物理内存 / 交换分区」两条进度条右侧各有一个小扫把按钮：

| 目标 | 做法 | 说明 |
|---|---|---|
| 物理内存 | `sync; echo 3 > /proc/sys/vm/drop_caches` | 释放 page cache / dentries / inodes。**不会**释放应用已占用的匿名内存，因此「已用」数字不会立刻下降，下降的是 cached |
| 交换分区 | 逐设备 `swapoff <dev>; swapon <dev>` | 把 swap 里的页换回物理内存后重建；设备名从 `/proc/swaps` 读取而非硬编码 |

- **清理前后各读一次 `/proc/meminfo` 求差**，把「释放了多少」用真实数字回报给用户，
  而不是弹一句「清理完成」；失败时把 `stderr` 原文带出来
- 清理会短暂占用 IO，结果提示 4 秒后自动收起

### 12. 耗电录制：Android 给不了「功耗」，只能自己算

「电源 → 耗电统计」是一个**有状态**的页面：手动开始一次录制，每秒累积采样，
停止后锁定本次记录。核心是把电池读数换算成功耗与续航，而这件事必须先把边界讲清楚——

**Android 原生不提供任何「功耗(W)」接口**，所有功耗都是算出来的。实现给两条路：

| 方法 | 公式 | 何时使用 | 精度 |
|---|---|---|---|
| **电流法** | `P = U × I` | 电流可读时（需 root） | 最高，逐秒可用 |
| **电量差法** | `P = Δ能量 / Δt`，`Δ能量 = Δ电量% × 额定能量(Wh)` | 无 root 时的回落 | **短录制下误差可达 ±100%** |

电量差法为什么不可靠：电量以 1% 为步进上报，28 Wh 电池跑 2 W 时 10 分钟只掉约 1.2%，
量化误差足以淹没信号。因此它**只在录制 ≥ 5 分钟时才被采信**，
否则宁可返回「不可用」，也不给一个假数字。

**电池能量**优先取 `BatteryManager.getLongProperty(BATTERY_PROPERTY_ENERGY_COUNTER)`
（单位 nWh，API 21+，是系统侧唯一的直接测量能量读数）。注意必须用 `getLongProperty`——
`getIntProperty` 返回 int，28 Wh 折合 2.8e10 nWh 会**溢出**，拿到的是垃圾值。
本机未上报该属性，因此回落到「设计容量 × 3.85 V」，并在界面上明确标注这是折算值。

**理论续航** = `剩余能量(Wh) ÷ 平均功耗(W)`。充电中不给——那时「还能用多久」没有意义；
若整段录制都在充电，则明确提示「未计算耗电功耗与续航」。

#### 12.0 容量节点的单位：`charge_full` 本来就是 µAh

`charge_full` / `charge_full_design` 报出的 raw 值**已经是 µAh**，直接存即可。
曾在模型层多除了一次 1000，于是「设计容量」在界面上显示成 **5 mAh**
（真机 raw 为 5,920,000 µAh = 5920 mAh，被除了两次 1000）。

判据：同一族的 `charge_counter` 就是 µAh（功耗求导一直按 µAh 计算），
而 `charge_full` 报出的 raw 与之同量级。修复后真机读数为 **5920 mAh**，
与该机型额定容量一致——这也是验证单位是否正确的最快方法：
**算出来的容量必须和机型标称值对得上**，差 1000 倍就是单位错。

#### 12.1 应用侧明细的诚实边界

Android 同样没有「按应用的耗电量」接口。这里以 **CPU 占用为代理指标**：
按应用累加每次采样的 CPU 百分比，再归一化成占比。

- 用**累加**而不是平均：「50% 占用持续 10 秒」与「5% 占用持续 100 秒」能耗并不相同，
  取平均会让短时高负载的应用被严重低估。
- 抓得到「谁在烧 CPU」，抓不到「谁在后台唤醒/联网」——界面上如实说明。
- 活跃时长取自 `UsageStats`，而它是**定期落盘**的，不是实时值：短录制内前后两次查询的
  累计值往往完全相同，差值恒为 0。此时显示「活跃时长待系统刷新」，而不是「前台 -」——
  后者读起来像「它没在前台待过」，与事实不符。
- 应用侧采样**每 10 秒一次**而不是每秒：root `top` 一次要上百毫秒，
  每秒调用会明显抬高录制本身的功耗，反而污染被测对象。

#### 12.2 图表必须用真实时间轴

电量曲线的 X 轴按**毫秒比例映射**，不是把采样点均匀铺满整幅宽度。
等距铺满时录 1 分钟与录 10 分钟的曲线长得一模一样，斜率失去意义——
而这一页要回答的恰恰是「电量掉得快还是慢」。

- X 轴默认 **0~10 分钟**，超出后按 **5 分钟向上取整**扩展（固定 10 分钟会让长录制挤在右端；
  只按实际时长缩放又会让曲线永远铺满全宽，同样看不出快慢）。
- Y 轴固定 **0 / 25 / 50 / 75 / 100%**，带刻度网格。
- 起点处画一条虚线基准，便于一眼比出「掉了多少」。

#### 12.3 录制器是单例，采样节拍由 ViewModel 驱动

`PowerRecorder` 与 `FpsRecorder` 同构：状态放单例，页面只读不写。
理由是录制要跨页面存活，且必须能在应用退到后台后继续，而 ViewModel 随 Activity 销毁而清理。
采样节拍复用 ViewModel 已有的 1 秒循环（那里已有完整的采集链路与 root 合并命令），
录制器只负责累积与计算。

### 13. ZRAM 容量调整的安全闸门

ZRAM 调整会走 `swapoff → reset → disksize → mkswap → swapon` **重建交换分区**，
期间正在使用交换区的应用会短暂卡顿，且改错容量不易回滚。因此默认**上锁**。

| 层 | 行为 |
|---|---|
| 界面 | 未开启时容量滑块与「应用并重建 / 取当前值」整体置灰 |
| ViewModel | `resizeZram()` 入口再判一次闸门，未开启直接 return |
| 有效条件 | `rootAvailable && zramResizeEnabled`——两者缺一不可 |

「UI 置灰 + VM 拒绝」是双保险：界面状态可能被别处改写，只在 UI 层挡无法保证
「未开启就绝不会真的重建交换分区」。

**容量范围 0 ~ 8 GB。`0` 不是「0 字节的分区」，而是关闭该 zram 交换设备**：

```
swapoff <dev>; echo 1 > reset; echo 0 > disksize      # 不做 mkswap / swapon
```

0 字节的设备无法格式化，`swapon` 必然失败，因此 0 走单独分支。

### 14. 性能调度：复用模块自己的配置文件，不另起一套控制通道

「性能调度」页对接两个第三方 Magisk 模块，控制点全部落在**模块自己就在用的那份配置**上：

| 目标 | 落点 | 依据 |
|---|---|---|
| Uperf 电源档位 | `Android/yc/uperf/cur_powermode.txt` | 该文件即 `uperf.json` 的 `switcher.switchInode`，uperf 守护进程用 inotify 监听它；模块自带 `script/powercfg_main.sh` 的**全部逻辑**也只是 `echo "$1" > 该文件` |
| Uperf 分应用规则 | `Android/yc/uperf/perapp_powermode.txt` | `uperf.json` 的 `switcher.perapp` |
| A-SOUL 全局与分游戏 | `/data/adb/naki/asopt.conf` | 模块 `customize.sh` 生成的同一份配置 |

收益是应用、模块自带的 WebUI、模块自身的脚本三者改的是同一份状态，不存在两套配置互相覆盖。
**代价是档位取值必须来自模块源码而不是常识**——`powercfg_main.sh` 只认
`powersave / balance / performance / fast / auto`（外加 pedestal 对应的 `crazy`），
凭常见调频器名字臆造会让状态文件写进去但 uperf 认不出来，表现为「点了按钮档位不变」的静默失败。

**「重启服务」不能调用 `initsvc.sh`。** 其 `uperf_start()` 是 `$BIN_PATH/uperf ...`——
**前台执行、不后台化**，从 shell 同步调用会永久阻塞在守护进程上，
并且会在已有实例之外**再拉起第二个 uperf**。因此实现为
「`killall uperf` → `setsid nohup` 分离重启」。

A-SOUL 侧可以安全地直接调用模块的 `service.sh`（最后一行是 `nohup ... &`），
仍额外加了 `[ -d /data/data/android ]` 前置判断，规避脚本内 `until` 等待循环在异常环境下死等。

**读取用一次合并的 root 命令**（带 `##段名` 标记），而不是每个字段各起一次 `su`——
后者在「进页面就刷新」的场景下会明显拖慢首屏。

### 15. UI 设计

设计语言取自一张浅色系性能仪表盘参考图：**数据卡片保持实底，只有悬浮导航用玻璃**。
完整实现细节（含 AGSL 折射的 SDF 位移场、backdrop 分层采样、官方配色值）见
[`docs/README.md`](docs/README.md) 第一部分，此处只记关键决策：

- **Miuix 0.9.4**（HyperOS 风格组件库）作为基础组件层；**Kyant0 `backdrop` / `capsule`**
  提供液态玻璃（底栏、胶囊、开关、滑块）。选它而非自绘的关键原因：
  自绘的每一层（棱光/描边/高光）都与 `drawBackdrop` 的内部渲染几何对不上，
  实测在底栏中部留下过一条边缘锐利的白色横带。**铁律：不要在 `drawBackdrop` 外
  再叠任何自绘的 `background` / `border` / 棱光。**
- **模糊只用在底部导航**：早期给顶栏 + 底栏 + 卡片全上真实模糊，后发现密集数据场景下
  实底卡片的文字对比度与分辨率明显更好，于是整层移除，改为按需使用
- **顶栏由根布局渲染唯一一次**（`OsTopBar`，`TopBarHeight = 56.dp`）：标题、返回按钮与
  动作按钮的尺寸/内边距/字号全部来自同一组常量，因此七类页面的顶栏**高度与基线完全一致**。
  页面本身不自绘标题，也无需为顶栏预留顶部留白
- **卡片一律不写外部小节标题**，且由结构保证：`SectionCard` **不再提供 `title` 参数**，
  想加标题只能写进卡片内部。分组说明统一用 `CardSectionLabel`
- **概览页按「结论 → 水位 → 明细」排列**：健康结论条先回答「算不算正常」，
  三张圆环卡回答「现在是多少」，帧率折线卡回答「稳不稳」，设备概况把低频静态信息压到最后
- **环内文字受「内切矩形」约束，不是内径宽度**——本品最容易被算错的地方。
  文字块四个角都要落在内圆内，最下面那行说明文字的**外沿**是最紧的约束点。
  按真实 ascent/descent 排版（名称 14.0 + 百分比 22.2 + 说明 10.8dp，行距 1dp）：
  三行总高 49.0dp，末行外沿距环心 24.5dp，可用宽度 = `2·√(r_in² − 24.5²)`；
  96dp 环内径 40dp → 环心限宽 63.2dp，安全线 57.2dp。
  **注意 `sp × 1.3` 不能当行高用**（21sp 真实行高 22.2dp，而 `21 × 1.3 = 27.3dp`
  有 23% 误差，足以翻转环尺寸的结论）
- **两列版的说明行必须极短**：`4320 MHz` 44.0dp、`222 MHz` 38.8dp 在安全线内；
  而 GPU 型号 `Adreno830v2` 需 57.8dp 已顶到边界，**所以概览只显示实时频率，型号移到详情页**。
  说明行一律去掉「当前 / 可用」这类前缀词
- **数值不可读时画空圈 + 灰字**（如 GPU 负载 -1），而不是 0% 的环——
  「读不到」和「负载为零」是两回事
- **2×2 四格等高靠 `SectionCard(contentHeight)`**：给卡内内容一个**最小高度**
  （`heightIn(min = ...)` 而非给卡片写死高度，内容超出时卡片仍会自然长高）
- **SoC 结温与电池温度必须是两个判据，不能合并**：`cpu.tempC` 读的是
  `/sys/class/thermal/thermal_zone0`（真机 `type` 为 `aoss-0`，是 SoC 结温），
  正常负载下就有 45~60 ℃。合并成一个 `tempC` 会得出「温度过高 57.9 ℃」与
  「电池健康 良好」自相矛盾的结论。现拆成 `socTempC`（85 ℃ Danger / 72 ℃ Warn）
  与 `batteryTempC`（45 ℃ Danger）两个维度，结论文案也标出来源
- **帧率格保留折线**：三个圆环回答「现在的百分比是多少」，帧率要回答「稳不稳」——
  一个瞬时百分比会把「稳定 60 帧」和「在 60 与 30 之间来回跳」画成同一个数字
- 图表全部 `Canvas` 手绘。**除「每核频率 / 占用」保留柱状图外，所有趋势一律用折线**——
  柱状适合比较同一时刻的多个离散对象，折线才适合表达随时间连续变化的量
- 页面切换用 `AnimatedContent` 交叉淡入淡出，判据取自动画参数而非闭包捕获的 state

### 16. 其他

- 预测性返回手势：`android:enableOnBackInvokedCallback="true"` + `enableEdgeToEdge()`，
  并用 `androidx.navigationevent` 的 `NavigationBackHandler` + `rememberNavigationEventState`
  接管返回逻辑（非概览页 → 回概览；概览页不拦截，交还系统执行退出动画）
- 开关状态持久化（`SharedPreferences`）；`BootReceiver` 在开机后按偏好恢复前台服务
- **应用运行在高刷上**：`MainActivity.requestHighestRefreshRate()` 在 `onCreate` 里
  把窗口的 `preferredDisplayModeId` 设到与当前分辨率一致、刷新率最高的 mode。
  不请求的话系统会按省电路由把应用锁在 60Hz，监控工具自己反而成了被监控对象。
  真机（1440×3168 @640dpi）取到 `modeId 1 = 120.00001 Hz`。
  概览帧率折线的 `autoMax` 上限也相应从 60 提到 120
- **不使用 LSPosed/Xposed**：全部功能在自身进程内通过 Root 完成，没有需要注入其他进程的场景；
  若声明 `xposedmodule=true` 而不提供 hook 实现，LSPosed 会把它列为可激活模块，
  激活后毫无作用，对用户是误导
- 所有采集在 `Dispatchers.IO` 执行，UI 仅订阅 `StateFlow`，不阻塞主线程

---

## 三、工程结构

```
app/src/main/java/com/osplus/tools/
├── MainActivity.kt              # 唯一 Activity，edge-to-edge + 预测性返回 + 请求最高刷新率
├── OsPlusApplication.kt         # 通知渠道（含历史遗留通道清理）
├── core/
│   ├── Shell.kt                 # su/sh 执行封装（root 探测缓存、节点读写、超时与并发读流）
│   ├── SystemProbe.kt           # 一次 root 命令采集全部受限节点
│   ├── CpuDataSource.kt         # CPU 信息、簇识别、增量占用率、频率与调速器控制
│   ├── GpuDataSource.kt         # kgsl / devfreq 多路径探测与频率控制
│   ├── MemDataSource.kt         # 内存、SWAP、ZRAM、swappiness
│   ├── BatteryDataSource.kt     # 电池信息 + ChargeController 充电控制
│   ├── ProcessDataSource.kt     # root top 全量进程列表
│   ├── PerfSchedDataSource.kt   # 性能调度：Uperf / A-SOUL 模块的配置读写与进程重启
│   ├── PowerStatsDataSource.kt  # 「使用情况访问」权限网关（应用维度数据的唯一前置条件）
│   ├── LiveMetrics.kt           # 实时指标快照（通知 / 悬浮窗的数据源）
│   ├── LiveNotif.kt             # 实时任务通知：呈现方式、显示项注册表、保活开关
│   ├── PowerRecorder.kt         # 耗电录制：逐秒累积、应用侧加权、平均功耗与理论续航计算
│   ├── Preferences.kt           # SharedPreferences（主题、开关、悬浮窗位置/不透明度等）
│   ├── FpsOverlayState.kt       # 悬浮窗开关与不透明度的跨层状态
│   └── FpsRecorder.kt           # Choreographer 逐帧统计 + 记录会话状态（应用与悬浮窗共享）
├── model/Models.kt              # 数据模型（含 MetricSample 采样点、FpsRecord）
├── vm/DeviceViewModel.kt        # 1 秒采样循环 + 累积趋势缓冲 + 全部控制入口
├── ui/
│   ├── OsPlusApp.kt             # 根布局：一级页路由 + 二级详情栈 + 液态玻璃底栏 + backdrop 记录
│   ├── theme/
│   │   ├── OsTokens.kt          # 设计令牌（OsColors / OsText）
│   │   └── Theme.kt             # OSPlusTheme
│   ├── components/
│   │   ├── Surfaces.kt          # osCard / liquidGlass / osTile / pressable / PageBackground
│   │   ├── LiquidGlass.kt       # 液态玻璃组件家族（底栏 / 开关 / 滑块 / 按钮）
│   │   ├── GlassShaders.kt      # AGSL 折射着色器
│   │   ├── Charts.kt            # 手绘图表（折线 / 多序列 / 真实时间轴 / 环 / 柱 / 频率宫格）+ 降采样
│   │   ├── Navigation.kt        # 统一顶栏（标题 / 返回 / 动作）
│   │   └── Common.kt            # SectionCard / CardSectionLabel / InfoRow / ChoiceChip / HealthBanner / LiquidNavTabs…
│   └── screen/
│       ├── OverviewScreen.kt        # 概览（健康结论 + 2×2 指标网格 + 设备概况）
│       ├── PerfScreen.kt            # 性能（进程摘要 + 可选时间窗趋势 + 调优入口）
│       ├── FpsScreen.kt             # 帧率记录与分析
│       ├── PowerScreen.kt           # 电源（电池态势 + 耗电/充电统计与控制）
│       ├── SettingsScreen.kt        # 设置（主题 / 采样 / 实时任务通知 / 权限 / 设备）
│       ├── PerfDetailScreens.kt     # 内存 / GPU / CPU 详情
│       ├── ProcessDetailScreen.kt   # 进程管理
│       ├── PerfSchedScreen.kt       # 性能调度（Uperf / A-SOUL 模块控制）
│       └── PowerDetailScreen.kt     # 电源三个统计页签（由 PowerScreen 承载）
├── service/FpsOverlayService.kt # 跨应用监视前台服务：实时任务通知 / 悬浮窗 / 保活锚点
└── receiver/BootReceiver.kt

app/src/test/java/com/osplus/tools/
├── DownsampleTest.kt            # 趋势降采样
├── FpsRecordTest.kt             # 帧率记录与 CSV 行格式
└── GpuDataSourceTest.kt         # GPU 频率单位判定
```

约 **12000 行 Kotlin**（主源码 + 测试）。

---

## 四、构建

环境：JDK 17、Android SDK（platform 37 + build-tools 37.0.0）、Gradle 9.7.1、AGP 9.4.1、Kotlin 2.4.20

```bash
cd C:/Work/OSPlus
export JAVA_HOME="C:/Program Files/Amazon Corretto/jdk17.0.20_10"
/c/Work/gradle/gradle-9.7.1/bin/gradle --no-daemon assembleDebug     # 调试包
/c/Work/gradle/gradle-9.7.1/bin/gradle --no-daemon assembleRelease   # 精简包
/c/Work/gradle/gradle-9.7.1/bin/gradle --no-daemon testDebugUnitTest # 单元测试
```

> AGP 9 起内置 Kotlin 支持，`org.jetbrains.kotlin.android` 插件必须移除，
> 仅保留 `org.jetbrains.kotlin.plugin.compose`。

安装与真机核查：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 实时任务通知是否被提升为置顶卡片
adb shell dumpsys notification --noredact | grep -E "PROMOTED|shortCritical|importance"
# 进程是否被系统冻结（1 = 已冻结）
adb shell "cat /sys/fs/cgroup/apps/uid_<uid>/pid_<pid>/cgroup.freeze"
```

---

## 五、已知限制

1. **需要 root 才能完整工作**。无 root 时仅内存与 CPU 频率可读，
   CPU 占用率、GPU、功耗、进程列表、所有控制功能均不可用（界面会给出提示）。
2. **后台实时刷新受厂商冻结策略限制**。ColorOS 会在应用退到后台约 5~10 秒后冻结整个进程，
   前台服务不足以豁免、root 也无法阻止。**「保活锚点」（1×1 不可见悬浮窗）可解决亮屏场景**，
   但需要悬浮窗权限；**息屏后仍会暂停**，亮屏自动恢复。详见第二节第 8 条。
3. **充电控制依赖内核节点**。不同机型暴露的节点名不同，应用会自动探测；
   未暴露则开关置灰并在「内核节点探测」中列出实际可用项。
4. **耗电统计为估算值**。Android 未开放按应用的真实耗电量接口，
   此处以 UsageStats 前台/可见时长加权计算占比，非硬件实测功耗。
5. **趋势数据是滚动窗口**。累积保留上限 1800 条（30 分钟），超过后丢弃最早的一条，
   因此长时间挂后台不会无限吃内存，但也看不到 30 分钟以前的历史；
   需要更长的留档请使用「帧率」页的记录功能（上限 7200 条 ≈ 2 小时）并导出 CSV。
6. **帧率记录**：录制期间每秒留档一条（上限 7200 条 ≈ 2 小时），CSV 通过 MediaStore 写入
   「下载 / OSPlus /」，无需存储权限。录制不随页面切换中断。跨应用显示需在「设置」里
   选择呈现方式（实时任务通知 / 悬浮窗）并授予相应权限（前者无需权限，后者需悬浮窗权限）；
   悬浮窗**轻点开始 / 停止记录，长按或拖动移动位置**，位置与不透明度都会被记住
   （不透明度下限 25%）。Android 13+ 还需通知权限（首次启动会自动申请）。
7. **实时任务通知最多显示 3 项指标**。这是系统 `MetricStyle` 的平台限制，
   超出的项会被静默丢弃；`MetricStyle` 的字体与列宽由系统模板决定，应用无法调整。
8. **ZRAM 容量调整会重建交换分区**（`reset` → `disksize` → `mkswap` → `swapon`），
   正在使用交换区的应用可能出现短暂卡顿。需先开启「允许调整」闸门，默认关闭；
   范围 0~8 GB，0 表示关闭交换分区。部分机型内核不允许运行时改小 zram，
   此时写入会失败且不产生副作用。
9. **性能调度页依赖第三方模块**。Uperf Game Turbo 与 A-SOUL Games Optimization
   均为独立 Magisk 模块，未安装时对应卡片只显示「未检测到模块」，其余功能不受影响。
   本页只读写这两个模块自己的配置文件与进程，不替代模块本身。
10. **耗电录制里没有一项是系统直接读出来的**。Android 不提供功耗、按应用耗电量、
    实时前台时长这三类接口，本页的数值分别由「电压 × 电流」「CPU 占用加权」
    「UsageStats 差分」推算而来，界面已逐项标明来源与局限（见第二节第 12 条）。
    无 root 时电流不可读，平均功耗退化为电量差法，**短录制误差可达 ±100%**，
    因此录制不足 5 分钟时不给结论。

---

## 六、版本沿革

| 版本 | 主题 |
|---|---|
| 1.3.0 | 信息架构改版：底栏收敛为概览 / 性能 / 帧率 / 电源，设置移出导航，统一顶栏 |
| 1.4.0 | 概览指标改回圆环（CPU / GPU / 内存），电池卡换成帧率折线卡 |
| 1.5.0 | 新增「性能调度」二级页（接管 Uperf / A-SOUL 两个模块） |
| 1.5.x | 玻璃组件换装官方 LiquidGlass；底栏加横向拖动切页 |
| 2.0.0 | 实时任务通知（可配置显示项 + 1 秒刷新 + 保活锚点）；ZRAM 调整闸门；`Shell` 超时与并发读流 |
| **2.1.0** | 新增「耗电统计」录制页；修复电池节点被 SELinux 静默跳过、容量单位多除 1000、顶栏复制无回执；清理死代码 |

### 2.1.0 的冗余清理明细

| 处置 | 对象 |
|---|---|
| 删除 | `RowScope.MiniBars`、`ProgressRow`、`StatTile`、`SegmentedTabs`——组件层，均已被后续实现取代且零引用 |
| 删除 | `osIsDark()`、`OsText.navLabel`、`GlassCornerRadius`——设计令牌，零引用 |
| 删除 | `NotifMetric.fromKey`、`tailUperfLog`——零引用 |
| 删除 | `PowerDetailScreen.formatDuration`——被 `formatSpan` 取代 |
| 删除 | **整条不可达的「近 24 小时 UsageStats 估算」链路**：`PowerStatsDataSource.read()`、`PowerUsageEntry`、`vm.powerUsage`、`vm.refreshPowerUsage`（UI 已被录制页取代，数据源随之成为死代码） |
| 新增 | `vm.refreshUsageAccess()`——只重读权限，供录制页进入时刷新 |
| 修复 | `PowerRecorder` 一处冗余的 `.toFloat()`（清理前编译器唯一的警告） |

清理后复扫 39 个文件，零引用声明只剩 `BootReceiver`——它由 `AndroidManifest.xml` 引用，
是纯 Kotlin 扫描的正常误报。编译 **零警告**。

---

## 七、相关文档

| 文档 | 内容 |
|---|---|
| [`docs/README.md`](docs/README.md) | 液态玻璃悬浮导航栏的完整实现（两层 backdrop、AGSL 折射 SDF 位移场、手势系统）；实时任务通知的可配置化、通道迁移、后台冻结约束与保活锚点的实测记录 |
| `dist/README.md` | 另一条独立交付线：把 Uperf / A-SOUL 两个 Magisk 模块打包成自带 WebUI 的可刷模块 |
