# OSPlus · Android 性能监视工具

包名 `com.osplus.tools` ｜ 版本 1.2.0 ｜ minSdk 33 (Android 13) ｜ targetSdk 36

一个完全自研的本地性能监视与调优工具。所有数据来自 `/proc`、`/sys` 与系统 API，
**不做任何云端上报**；涉及内核写入的功能全部经 root 执行。

---

## 一、功能清单

信息架构：**无顶部标题栏**，底部悬浮导航分 **概览 / 实时 / 帧率 / 设置** 四个一级页（纯图标，不显示文字）；
概览页的卡片可下钻到 **内存 / GPU / CPU / 进程 / 电源** 五个二级详情页，二级页左上角浮出返回按钮。

| 页面 | 能力 | 数据来源 |
|---|---|---|
| **概览**（一级） | 内存（含**物理内存 / 交换分区一键清理**）、GPU（**负载百分比直接显示在环图中心**）、CPU（占用环 + 关键频率 + **各核心频率瓦片宫格**）、电源、设备概况五张卡片，一眼看清水位；点卡片下钻详情 | 见下 |
| **实时**（一级） | **进程摘要置顶**（图标 / 名称 / CPU%，点卡片进进程管理）；CPU 总占用、内存占用、GPU 频率、整机功耗、实时帧率、电池温度六条**累积折线趋势**；每核占用与频率柱状图 | `/proc`、`/sys` |
| **帧率**（一级） | 基于 Choreographer 的实时 FPS、平均/最大帧耗时、卡顿计数；**30 秒 / 1 / 5 / 10 分钟 / 全部 多档分析窗口**；全部为**折线趋势**；**录制期间每秒同步留档每核占用与频率、GPU、内存、功耗、温度**，并实时显示递增的**记录时长**；**一键导出 CSV**；**跨应用帧率悬浮窗（轻点开始/停止记录，长按拖动位置，可调不透明度）**；系统 `dumpsys gfxinfo` 明细 | 系统 API |
| **设置**（一级） | **主题切换（跟随系统 / 浅色 / 深色）与壁纸取色（Monet）开关**；Root 状态与 `su` 路径、使用情况访问、悬浮窗、通知权限；采样开关与间隔；设备与内核信息 | — |
| **内存详情** | 占用率与 SWAP 累积曲线；内存明细；ZRAM 容量调整（重建交换分区）；swappiness 调节 | `/proc/meminfo`、`/proc/swaps`、`/sys/block/zram*` |
| **GPU 详情** | 频率/负载累积曲线；型号 / 当前频率 / 调速器 / 可用频率表；**调速器切换**；频率上限调节 | `kgsl-3d0` sysfs |
| **CPU 详情** | 总占用与每核占用柱状图；核心簇频率与量程；**调速器切换**；**按核心选择目标后以「挡位」方式锁定频率（含调频策略组影响范围提示与写入回读校验）** | `cpufreq` sysfs |
| **进程详情** | 全量进程列表（**应用图标** / CPU% / 常驻内存 / 用户 / 状态），按 CPU 或内存排序，强制停止与结束进程 | root `top -b -n 1` |
| **电源详情** | **耗电统计**：近 24 小时各应用**图标**、前台/可见时长与估算耗电占比；**充电统计**：功耗与电池温度累积曲线、电压/电流/功率/容量/循环次数；**充电控制**：充电开关、充电电流上限 | `UsageStatsManager`、`power_supply` sysfs |
| **交互** | 底部导航为**液态玻璃**材质、纯图标（无文字），点选切换一级页；二级页左上角悬浮返回按钮；预测性返回手势：二级详情页返回回到所属一级页，一级页返回回到概览，概览页按返回正常退出应用 | — |

### 实时采样规格

- 采样间隔 **1 秒**；趋势数据**累积保留，上限 1800 条（30 分钟）**，超出后按滚动窗口丢弃最早一条
- 因此趋势窗口会**随运行时间不断变长**（实时页趋势卡片顶部显示「已累积 X 分 Y 秒 · 每秒 1 次采样」，
  时间轴左端显示 `-mm:ss`），而不是固定的几秒；绘制时统一降采样到 **60 个槽位**，
  保证曲线密度稳定、长时间运行也不掉帧
- CPU 占用率由 `/proc/stat` 相邻两次采样求差得到
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

调速器与核心选择的**选中态用整块变色表达（底色 + 描边 + 文字色），不在文案前打勾**：
打勾会改变文案宽度，同一行的胶囊在选中/取消时宽度跳动，行宽不稳定。

### 6. 录制不能绑定在页面生命周期上

帧率页最初在 `DisposableEffect.onDispose` 里停止录制，本意是「离开应用就停」，
但 **Compose 的 onDispose 在切换标签页时同样会触发**，结果变成
「一切到别的页面，记录就断了」。

录制状态应只由用户显式开关控制，因此：
- 录制状态与数据都放在 `ViewModel`（跨标签页存活），页面只读不写
- 页面 `onDispose` 不再做任何停止动作
- **记录会话状态进一步下沉到 `FpsRecorder` 单例**：桌面悬浮窗轻点也能开关记录，
  而悬浮窗在应用退到后台后依然存在，若状态只存在 ViewModel 里，
  就会出现「悬浮窗已开始记录、页面开关却显示关闭」的撕裂。两边读写同一个 `StateFlow`。
- 同理，`FpsRecorder` 用 `overlayHolds` 标志仲裁「谁还在用统计器」：
  结束记录时若悬浮窗仍在运行则保留逐帧回调（否则窗上数字会冻结），
  悬浮窗销毁时若仍在记录则不停统计器（否则记录被中断）。

跨应用记录依赖前台服务保活（否则进程进入 cached 状态会被系统冻结）：
- `FpsOverlayService` 用共享的 `FpsRecorder` 作为采样源（悬浮窗是本进程的可见窗口，
  系统会持续投递 vsync），因此切到其他应用后帧率仍在采集
- 未开启悬浮窗时界面会提示「切到其他应用后可能停止采集」

**悬浮窗手势：轻点切换记录，长按或拖动移动位置。** 两种意图按「按住时长 + 位移」区分：
位移超过 8 px 立即进入拖动（快速拖不会误判成轻点），按住超过 320 ms 也进入拖动
（长按即准备移动，避免长按抬起时被当成点击），两者都不满足且按压很短才算轻点。
记录中窗上数值前会显示一个红点，通知文案也同步切换，避免「记没记」只能靠猜。

**悬浮窗开关绑定服务的真实运行状态**（`FpsOverlayState`），而不是偏好设置里的值：
应用被强停后服务已死，若开关仍读偏好就会显示成「开启」，
用户一点反而把本就没运行的服务「关掉」。服务在 `onCreate/onDestroy` 写入真实状态，
界面订阅它；应用启动时若偏好为开则重新拉起服务，使状态与实际一致。

### 6.1 悬浮窗透明度必须改 View，不能改 Window（Android 12+ 触摸限制）

1.2.0 曾出现一个 bug：**悬浮窗不透明度调到 50% 以下后，轻点 / 长按 / 拖动全部失效**。

根因是 Android 12 引入的「**不受信任的触摸事件**」限制。对
`TYPE_APPLICATION_OVERLAY` 窗口，系统会看它的 `LayoutParams.alpha`：

- `alpha > InputManager.getMaximumObscuringOpacityForTouch()`（当前 **0.8**）→ 窗口「足够遮挡」，
  是正常的触摸目标；
- `alpha <= 0.8` → 窗口被判定为「遮挡不足」，**系统不再把触摸事件投递给它**，
  于是整个悬浮窗变成只显示、不可交互。logcat 会打印
  `Untrusted touch due to occlusion by com.osplus.tools`。

而当时的不透明度正是直接写 `LayoutParams.alpha`，因此滑块一拉低，悬浮窗就「死」了。

**修法：窗口 `alpha` 恒为 `1.0`，视觉透明度改由 `View.alpha` 承担。**

```kotlin
params.alpha = 1f          // 窗口在输入层面永远是「足够遮挡」的触摸目标
overlayView.alpha = value  // 透明度只影响绘制，不影响输入分发
```

注意这与官方文档里「要让触摸**穿透**下去，必须在窗口级别降低不透明度」并不矛盾——
那是「主动放弃接收触摸」的场景，需求相反。我们既要半透明外观、又要自己接收手势，
所以只能把两层 alpha 拆开用。

顺带一提：这个阈值是**窗口**层面的，`View.alpha` 无论调到多低都不会触发该限制，
因此修复后 25% 的不透明度依然可点可拖。

### 7. 进程列表

应用无法读取其他进程的 `/proc/<pid>`。改用 root 执行一次：

```
top -b -n 1 -q -o %CPU,RES,PID,USER,S,ARGS
```

一次调用即可获得全部 ~1000 个进程的 CPU 占用、常驻内存与名称。
由 `USER` 列（`u{userId}_a{appId}`）反推 uid，再经 `PackageManager` 映射到包名。
采集用的 `top` / `ps` 辅助进程已从列表中过滤。

### 8. 内存清理（drop_caches / 重建交换分区）

概览页「物理内存 / 交换分区」两条进度条右侧各有一个小扫把按钮，点击执行清理：

| 目标 | 做法 | 说明 |
|---|---|---|
| 物理内存 | `sync; echo 3 > /proc/sys/vm/drop_caches` | 释放 page cache / dentries / inodes。**不会**释放应用已占用的匿名内存，因此「已用」数字不会立刻下降，下降的是 cached |
| 交换分区 | 逐设备 `swapoff <dev>; swapon <dev>` | 把 swap 里的页换回物理内存后重建，从而清空 swap 占用；设备名从 `/proc/swaps` 读取而非硬编码 |

两点实现上的考虑：
- **清理前后各读一次 `/proc/meminfo` 求差**，把「释放了多少」用真实数字回报给用户，
  而不是弹一句「清理完成」；失败时也把 `stderr` 原文带出来（多为缺 root）
- 清理会短暂占用 IO（swapoff 需要把页读回内存），因此结果提示 4 秒后自动收起，
  避免长期占据内存卡片

### 9. UI 设计

设计语言取自一张浅色系性能仪表盘参考图：**数据卡片保持实底，只有悬浮导航用玻璃**。

- **Miuix 0.9.4**（HyperOS 风格组件库）作为基础组件层（`Text` / `Switch` / `Slider` / `Button` / `Icon`）
- **背景模糊只用在底部导航，不再铺给卡片**：早期版本给顶栏 + 底栏 + 卡片全上真实背景模糊，
  后来发现密集数据场景下实底卡片的文字对比度与分辨率明显更好，于是整层移除。
  现在改为**按需**使用——只有底部悬浮导航（`liquidGlass`）走 `miuix-blur` 的真实高斯模糊，
  模糊区域仅一条胶囊大小，GPU 开销可控；数据卡片继续用 `osCard()` 实底
- **无顶部标题栏**：页面标题占掉一整行却只承载一个词，去掉后内容区直接顶到状态栏下方，
  纵向空间还给数据本身；二级页的返回入口改为左上角悬浮圆形按钮
- **底部导航为液态玻璃**：`rememberLayerBackdrop()` 把「背景 + 页面内容」整层记录进 `GraphicsLayer`
  （导航条自身不在其中，否则会把自己的高光也糊进去），导航条再对该层做
  `drawBackdrop(blur(26.dp))` 取真实模糊背景，之上叠 `liquidGlass()` 的半透明着色、
  顶光竖向渐变与线性渐变高光描边。内容滚到导航条下方时会被模糊后透出来，形成悬浮层次
- 底栏：Apple 风格悬浮胶囊，**只显示图标不显示文字**——四个一级页的图标语义已足够明确，
  去掉文字后胶囊更窄、更少遮挡内容；选中项图标着品牌色并带浅色底
- **设计令牌集中管理**（`ui/theme/OsTokens.kt`）：`OsColors` 保存浅/深两套配色，
  `OsText` 保存字号层级；配色以 `MiuixTheme.colorScheme.background` 的感知亮度自动判定深浅色，
  因此 Miuix 自带的 `Slider` / `Switch` 会自动采用同一套品牌色
- **卡片一律不写外部小节标题**：概览页卡片内部已有「内存 / GPU / CPU / 电池」圆环标签，
  再叠一行同名标题属于重复信息；二级详情页（内存 / GPU / CPU / 进程 / 电源）也全部去掉，
  与概览页统一。纯控制型卡片（调速器、频率上限、充电电流上限、内核节点探测等）
  改在**卡片内部**用一行 `CardSectionLabel` 小字说明用途，避免只剩一排胶囊/滑块看不出是什么
- 自定义 `Modifier` 扩展统一表面：`osCard()`（白底 + 发丝描边 + 极轻投影）、
  `liquidGlass()`（半透明 + 顶光渐变 + 渐变高光描边 + 大软投影，仅用于悬浮导航）、
  `osTile()`（卡内次级填充块）、`pressable()`（整体轻微淡出，替代水波纹）
- 图表全部 `Canvas` 手绘：`RingChart`（粗圆头圆环，**GPU 卡直接把负载百分比放进环心**）、
  `LineChart`（折线 + 面积渐变）、`CoreBarsChart`、`CoreFreqGrid`（频率瓦片宫格 + 迷你柱群）。
  **除「每核频率 / 占用」保留柱状图外，所有趋势一律用折线**——柱状更适合比较同一时刻的多个离散对象，
  折线才适合表达随时间连续变化的量；此前柱状/折线可切换的选项已移除，避免同一份数据两种读法
- 单选胶囊 `ChoiceChip`：选中即整块变色，用于调速器与核心选择
- 页面切换用 `AnimatedContent` 交叉淡入淡出，判据取自动画参数而非闭包捕获的 state，
  避免过渡期间新旧页面串帧
- 页面背景为中性浅灰 + 顶部极淡品牌色晕染，不使用大面积纯色

### 10. 其他

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
│   ├── Preferences.kt           # SharedPreferences（悬浮窗位置 / 不透明度等）
│   ├── FpsOverlayState.kt       # 悬浮窗开关与不透明度的跨层状态
│   └── FpsRecorder.kt           # Choreographer 逐帧统计 + 记录会话状态（应用与悬浮窗共享）
├── model/Models.kt              # 数据模型（含 MetricSample 采样点）
├── vm/DeviceViewModel.kt        # 1 秒采样循环 + 累积趋势缓冲 + 全部控制入口
├── ui/
│   ├── OsPlusApp.kt             # 根布局：一级页路由 + 二级详情栈 + 液态玻璃底栏 + backdrop 记录
│   ├── theme/
│   │   ├── OsTokens.kt          # 设计令牌（OsColors / OsText）
│   │   └── Theme.kt             # OSPlusTheme
│   ├── components/
│   │   ├── Surfaces.kt          # osCard / liquidGlass / osTile / pressable / PageBackground
│   │   ├── Charts.kt            # 手绘图表（折线 / 环 / 柱）+ 降采样 + 时间轴文案
│   │   ├── Navigation.kt        # 液态玻璃悬浮底栏 + 二级页悬浮返回按钮
│   │   └── Common.kt            # SectionCard / CardSectionLabel / InfoRow / ChoiceChip / SegmentedTabs…
│   └── screen/
│       ├── OverviewScreen.kt        # 概览（唯一主页，卡片下钻入口）
│       ├── RealtimeScreen.kt        # 实时（进程摘要 + 累积趋势）
│       ├── FpsScreen.kt             # 帧率记录与分析
│       ├── SettingsScreen.kt        # 设置
│       ├── PerfDetailScreens.kt     # 内存 / GPU / CPU 详情
│       ├── ProcessDetailScreen.kt   # 进程管理
│       └── PowerDetailScreen.kt     # 电源详情
├── service/FpsOverlayService.kt # 跨应用帧率悬浮窗（轻点记录 / 长按拖动 / 可调不透明度）
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
4. **趋势数据是滚动窗口**。累积保留上限 1800 条（30 分钟），超过后丢弃最早的一条，
   因此长时间挂后台不会无限吃内存，但也看不到 30 分钟以前的历史；
   需要更长的留档请使用「帧率」页的记录功能（上限 7200 条 ≈ 2 小时）并导出 CSV。
5. **帧率记录**：录制期间每秒留档一条（上限 7200 条 ≈ 2 小时），CSV 通过 MediaStore 写入
   「下载 / OSPlus /」，无需存储权限。录制不随页面切换中断；**跨应用记录需开启悬浮窗**
   （前台服务保活，否则进程被冻结后停止采集）；跨应用显示需在「帧率」页打开
   「悬浮窗显示并跨应用记录」开关，并授予悬浮窗权限。悬浮窗**轻点开始 / 停止记录，
   长按或拖动移动位置**，位置与不透明度都会被记住（不透明度下限 25%）。Android 13+ 还需通知权限，
   否则前台服务通知不可见（首次启动会自动申请）。
6. **ZRAM 容量调整会重建交换分区**（`reset` → `disksize` → `mkswap` → `swapon`），
   正在使用交换区的应用可能出现短暂卡顿。界面已给出明确警示，但仍建议在设备空闲时操作；
   部分机型内核不允许运行时改小 zram，此时写入会失败且不产生副作用。
