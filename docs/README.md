# OSPlus 技术实现：液态玻璃悬浮导航栏 & 实时任务通知

本文记录 OSPlus 2.0.0 两个核心特性的完整制作过程：**液态玻璃悬浮导航栏**（含全应用玻璃组件家族）
与**实时任务通知**（Android 16+ Live Updates，替代悬浮窗的跨应用状态卡）。
所有代码均可在仓库中按文中路径找到，参数与踩坑均为真机（PJZ110 / Android 17）实测结论。

---

## 第一部分：液态玻璃悬浮导航栏

### 1. 组件全景

| 组件 | 文件 | 作用 |
|---|---|---|
| `LiquidBottomBar` | `ui/components/LiquidGlass.kt` | 底部悬浮导航栏（本文主角）：玻璃底 + 选中胶囊 + 点击/拖动切页 |
| `LiquidBarItem` | 同上 | 底栏条目模型（label + icon） |
| `OsTopBar` / `OsTopBarAction` / `OsTopBarPillAction` | `ui/components/Navigation.kt` | 统一顶栏 + 圆形/胶囊玻璃按钮（**正圆 36dp**，均由原版 `LiquidButton` 渲染） |
| `LiquidNavTabs` | `ui/components/Common.kt` | 页内分段控件（内部即原版 `LiquidBottomTabs`，13 处调用点共用） |
| `ChoiceChip` | 同上 | 单选胶囊（原版 `LiquidButton` + tint 浅灰保底可见） |
| `LiquidToggle` / `LiquidSlider` | `ui/components/LiquidGlass.kt` | 全应用换装的开关 / 滑块 |
| `LiquidButton` / `LiquidBottomTabs` / `LiquidBottomTab` / `LiquidToggle` / `LiquidSlider` / `LiquidUtils` | `ui/liquid/` | Kyant0 原版移植件——**渲染主体**，逻辑与上游保持一致 |

> 2.5.0 起，**按钮没有适配层**：页面内按钮、顶栏圆形/胶囊按钮、`ChoiceChip` 一律由调用点
> 直接引用 `ui/liquid/LiquidButton`。原版把 `height(48.dp)` 与 `padding(horizontal = 16.dp)`
> 写死在组件内、外部 modifier 覆盖不了，因此给原版加了两个带默认值的参数
> （`height` / `horizontalPadding`）——默认值即原版值，顶栏传 `36dp` 得到正圆。
> 分段条则相反：`LiquidNavTabs` 保留名字与签名、内部换成原版 `LiquidBottomTabs`，
> 让 13 处调用点零改动。

### 2. 技术选型：用 Kyant0 官方 backdrop 库，不自绘

```kotlin
// app/build.gradle.kts
implementation("io.github.kyant0:backdrop:2.0.1")   // 液态玻璃核心（drawBackdrop / effects / shadow / highlight）
implementation("io.github.kyant0:capsule:2.1.3")
```

选型理由（也是一部踩坑史，按时间顺序）：

1. **第一代：miuix-blur**。只有高斯模糊，缺 `effects`（vibrancy）、`shadow`（Shadow / InnerShadow）、
   `Highlight` 预置样式也不一致——拼不出官方「玻璃」的质感。
2. **第二代：AGSL 自绘**（`RuntimeShader` + `RenderEffect.createChainEffect`，平台侧拼链再桥回 Compose）。
   能跑，但**任何在 `drawBackdrop` 之外自绘的层（棱光/描边/高光）都与内部渲染几何对不上**——
   实测在底栏中部留下一条边缘锐利的白色横带，逐层删除都无效，因为错位在绘制管线内部。
3. **第三代（现行）：`io.github.kyant0:backdrop`**。官方 catalog 组件
   （LiquidBottomTabs / LiquidButton / LiquidToggle / LiquidSlider）用的就是这套 API，
   玻璃的每一层都在 `drawBackdrop` 同一条着色器链里完成，共用同一套渲染几何，不存在错位。

> 铁律：**不要在 `drawBackdrop` 外再叠任何自绘的 `background` / `border` / 棱光**。
> 宁可少一层，也不要错位的一层。

### 3. 背景采样：两层 backdrop（防自采样拖影）

玻璃的本质是**实时采样它背后的内容**。`ui/OsPlusApp.kt` 里记录了两层 `GraphicsLayer`：

```kotlin
val backdrop = rememberLayerBackdrop()       // ① 背景 + 顶栏 + 页面内容 → 给底栏采样
val topBarBackdrop = rememberLayerBackdrop() // ② 纯背景 → 只给顶栏按钮采样

Box(Modifier.fillMaxSize()) {
    Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {   // ① 整层被记录
        Box(Modifier.fillMaxSize().layerBackdrop(topBarBackdrop)) {
            PageBackground()                                 // ② 纯背景被记录
        }
        Column {
            OsTopBar(backdrop = topBarBackdrop, ...)         // 顶栏按钮只采 ②
            // 页面内容…
        }
    }
    LiquidBottomBar(backdrop = backdrop, ...)                // 底栏在记录层外，采 ①
}
```

两条规则：

- **底栏自身绝不能在被记录层内**——否则它会把自己的高光也糊进去。
- **顶栏在被记录层 ① 内部**，顶栏按钮若直接采样 ①，采到的就是「包含按钮自己」的上一帧，
  逐帧累积成**拖影**。解法是单独录一层纯背景（②）给按钮采样。
  官方 `LiquidToggle` 遇到同样问题时给轨道单独开 `trackBackdrop`，是同一个思路。

另一个布局坑：`LiquidBottomBar` 用 `BoxWithConstraints(contentAlignment = Center)`，
选中胶囊只有一格宽，**必须显式 `.align(Alignment.CenterStart)`**——
不指定时胶囊会被先居中（左缘落在 1.5×格宽处），再叠加偏移量就整体右偏，
表现为「概览高亮、胶囊却卡在性能和帧率之间」。

### 4. 底栏材质：静止 = 纯毛玻璃，触摸时折射渐入

全家族统一的材质规则：**`blur` 常开，`lens`（透视/折射）乘以按压系数 `press` 渐入**。
按压态来自两个来源：点按的 `interactionSource.collectIsPressedAsState()` 与
拖动的 `onDragStarted/onDragStopped`，任一为真即视为「正在触摸」，
经 `spring(dampingRatio = 0.72f, stiffness = 480f)` 平滑成 0→1。

```kotlin
// 玻璃底（整条）
Modifier.drawBackdrop(
    backdrop = backdrop,
    shape = { RoundedCornerShape(percent = 50) },
    effects = {
        vibrancy()
        blur(8f.dp.toPx())                                  // 静止：全额毛玻璃
        lens(24f.dp.toPx() * barPress, 24f.dp.toPx() * barPress)  // 触摸：折射渐入
    },
    highlight = { Highlight.Default },
    onDrawSurface = { drawRect(container) },                // 官方容器色 #FAFAFA/#121212 @ 0.4
)

// 选中胶囊（单一覆盖层，非逐 item 自绘）
Modifier.drawBackdrop(
    backdrop = backdrop,
    shape = { RoundedCornerShape(percent = 50) },
    effects = {
        blur(8f.dp.toPx())
        lens(10f.dp.toPx() * barPress, 14f.dp.toPx() * barPress, chromaticAberration = true)
    },
    highlight = { Highlight.Default },
    shadow = { Shadow(alpha = 0.10f) },
    innerShadow = { InnerShadow(radius = 8f.dp, alpha = 1f) },
    onDrawSurface = { drawRect(accent.copy(alpha = 0.14f)) },   // 主色淡染
)
```

顶栏玻璃按钮（`OsTopBarAction` 圆形 / `OsTopBarPillAction` 图标+文字胶囊）同规则，
只是按钮小、模糊更轻：`vibrancy() + blur(2.dp) + lens(12.dp * press, 24.dp * press)`。

**模糊半径的权衡**（为什么不能一味调大）：折射要能被看见，背景必须留下可辨认的结构当参照物。
26dp 时一行小字、一条 1px 趋势线都已被糊成匀质底噪，采样位移再大也没有对比，
真机上表现为「折射完全没生效」——实际是模糊把参照物抹掉了。
14dp 是「背景仍可辨形」与「足够奶油」的平衡点（`Navigation.kt` 的 `GlassBlurRadius`），
底栏整条取 8dp、小按钮取 2dp。同理，玻璃着色透明度取 **0.4**——
alpha 到 0.55 以上时背景已被压到看不见，再大的折射位移也无参照物。

### 5. 手势系统：点击 + 横向拖动统一（最易踩坑的部分）

底栏在一个 `BoxWithConstraints` 上挂了**两个独立的 `pointerInput(Unit)`**：

```kotlin
.pointerInput(Unit) {
    detectHorizontalDragGestures(
        onDragStart = { tapStartX = it.x; barPressed = true },
        onHorizontalDrag = { _, dragAmount -> dragOffsetPx += dragAmount },
        onDragEnd = {
            if (abs(dragOffsetPx) >= 12f) {
                // 拖动：按胶囊松手位置除以格宽四舍五入，可一次跨多格
                val cellW = size.width / itemCount.toFloat()
                val next = (currentSelected + round(dragOffsetPx / cellW).toInt())
                    .coerceIn(0, itemCount - 1)
                if (next != currentSelected) currentOnSelect(next)
            } else {
                // 越过 touch slop 但位移不足：按起始落点视作点按
                val itemWidthPx = size.width / itemCount.toFloat()
                currentOnSelect((tapStartX / itemWidthPx).toInt().coerceIn(0, itemCount - 1))
            }
            dragOffsetPx = 0f; barPressed = false
        },
    )
}
// 点按检测必须独立：detectHorizontalDragGestures 的回调只在横向越过
// touch slop 后触发，干净的「点一下」根本不会走到它的 onDragEnd。
// 之前把选 tab 写在 onDragEnd 里，结果整个底栏只能拖、不能点。
.pointerInput(Unit) {
    detectTapGestures(
        onPress = { barPressed = true; tryAwaitRelease(); barPressed = false },
        onTap = { pos -> /* 按 pos.x 落点判 tab 并切换 */ },
    )
}
```

要点清单：

1. **点按逻辑绝不能写进拖动检测的 onDragEnd**——必须另挂独立 `detectTapGestures`。
2. 拖动检测在前、点按检测在后：真拖动时拖动检测先消费事件，
   点按检测看到已消费的移动会自动放弃，两种手势互不打架。
3. 判定阈值：位移 **≥ 12px** 走拖动切页（按格取整，可跨多格）；不足则按落点判 tab。
4. `pointerInput` 的 key 用 `Unit`（只建一次），最新的 `selectedIndex` / `onSelect`
   用 `rememberUpdatedState` 喂进手势闭包——否则每次选中变化重建闭包，
   会把进行中的拖动拦腰打断。
5. 选中胶囊位置由**单一 spring 驱动的绝对坐标**表达：
   `pillTarget = selectedIndex * itemWidthPx + dragOffsetPx`，
   `spring(dampingRatio = 0.82f, stiffness = 520f)`。拖动时经弹簧阻尼跟手，
   切页时平滑滑到新位——拆成两段动画会出现点选瞬移、松手过冲。
6. edge-to-edge 下必须 `.windowInsetsPadding(WindowInsets.navigationBars)`，
   否则三键导航会把整条压在按键下面。

### 6. 全家族组件的实现要点

- **`LiquidToggle` 修复「只能拖不能点」**（顺序敏感，勿重蹈）：
  ```kotlin
  var didDrag by remember { mutableStateOf(false) }
  Modifier
      .draggable(Orientation.Horizontal,   // draggable 必须在 pressable 外面：
          rememberDraggableState { d ->     // 外层先拿事件，越过 touchSlop 才消费，
              if (abs(d) > 6f) { didDrag = true; onCheckedChange(d > 0f) }  // 没越过的「点一下」落到 pressable
          },
          onDragStarted = { didDrag = false; dragPressed = true },
          onDragStopped = { didDrag = false; dragPressed = false })
      .pressable(interactionSource = interaction) {
          if (!didDrag) onCheckedChange(!checked)   // didDrag 守卫：拖过不再当作点击
      }
  ```
- **`LiquidSlider`**：视觉轨道 6dp，但点按热区放大到整行 24dp（6dp 细缝手指点不中）；
  滑块 40×24dp，`Highlight.Ambient` + `Shadow` + `InnerShadow`，
  `onDrawSurface = drawRect(White.copy(alpha = 1f - progress))`。
  补 `onValueChangeFinished`（轨道 tap 后、thumb onDragStopped 后回调），兼容原 miuix 用法。
- **`lerp` 重载歧义**：作用域同时有 `Color.lerp` 与 `Float.lerp` 时调用会解析失败——
  Float 场景直接算 `start + (end - start) * frac`，不要走 `lerp`。
- **`onDrawSurface` 不是可组合作用域**：里面不能调 `@Composable` 的取色函数，
  颜色必须在 composable 上下文里先取好。
- **无 backdrop 兜底**：按钮多数坐在不透明卡片上，采样一片纯色得不到任何玻璃效果。
  传 `backdrop = null` 时退化为 `glassStaticSurface`——只铺官方容器色，**不画自绘棱光**。
  另外严禁让控件采样「包含自己」的内容层，那会逐帧累积成拖影。

### 7. 配色（官方值，不自行发挥）

| 用途 | 浅色 | 深色 |
|---|---|---|
| 强调色（导航/按钮/滑块） | `#0088FF` | `#0091FF` |
| 开关强调色 | `#34C759` | `#30D158` |
| 玻璃容器色 | `#FAFAFA` @ 0.4 | `#121212` @ 0.4 |
| 轨道色 | `#787878` @ 0.2 | `#787880` @ 0.36 |

深浅判定跟**应用主题**（`osColors().isDark`）而非系统主题——
手动选深色而系统是浅色时，玻璃若按系统取浅色会出现发灰的割裂感。

---

## 第二部分：实时任务通知（Live Updates）

### 1. 设计目标：通知代替悬浮窗

2.0.0 之前跨应用帧率显示靠 `TYPE_APPLICATION_OVERLAY` 悬浮窗（需 `SYSTEM_ALERT_WINDOW`
权限 + 「不受信任触摸」等一系列适配）。2.0.0 起改由**实时任务通知**承担：

- 前台服务常驻（`specialUse` 类型），状态卡片进系统通知抽屉顶部，锁屏可见；
- **状态栏芯片常驻显示实时帧率**（代替悬浮窗的跨应用读数）；
- 通知上直接带「开始记录 / 停止记录」按钮；
- 不再需要悬浮窗权限，`addOverlay()` 代码保留以备回滚。

### 2. 服务骨架（`service/FpsOverlayService.kt`）

```kotlin
override fun onCreate() {
    startForeground(NOTIFICATION_ID, buildNotification())   // 0x0521
    FpsRecorder.overlayHolds = true                          // 与录制共用逐帧统计器
    FpsRecorder.start()
    // 每秒采样快照或录制状态变化 → 重建通知
    scope.launch {
        combine(LiveMetrics.snapshot, FpsRecorder.recording) { s, rec -> s to rec }
            .collectLatest { updateNotification() }
    }
}

override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_TOGGLE_RECORDING) {         // 通知按钮回调
        FpsRecorder.toggleRecording()
        updateNotification()
    }
    return START_STICKY
}
```

数据链路：`DeviceViewModel` 每秒采样 → `LiveMetrics.update(cpuLoad, gpuLoad, cpuFreqMhz,
gpuMhz, memAvailKb, memTotalKb)` → `LiveMetrics.snapshot: StateFlow<Snapshot>`；
帧率来自 `FpsRecorder`（Choreographer 逐帧统计）。`LiveMetrics` 只是跨组件状态中转，
避免通知重复拉起 root 采样。

### 3. Android 16+ 实时任务通知：官方资格清单

「实时任务通知」= Android 16（API 36，`Build.VERSION_CODES.BAKLAVA`）的
**Live Updates / promoted ongoing**：满足全部条件后系统把 ongoing 通知提升为
抽屉顶部卡片，`dumpsys` 里可见 `FLAG_PROMOTED_ONGOING`。逐项要求：

| # | 要求 | 本项目做法 |
|---|---|---|
| 1 | manifest 声明权限 | `<uses-permission android:name="android.permission.POST_PROMOTED_NOTIFICATIONS" />` |
| 2 | `setOngoing(true)` | ✅ |
| 3 | `setRequestPromotedOngoing(true)` | ✅ |
| 4 | 使用**标准样式**（MetricStyle / ProgressStyle / BigTextStyle / CallStyle） | `Notification.MetricStyle` |
| 5 | 有 `contentTitle` | 「OSPlus 实时状态」/「OSPlus 正在记录」 |
| 6 | 通道重要性不得为 `IMPORTANCE_MIN` | `IMPORTANCE_HIGH` |
| 7 | **禁止 customContentView / RemoteViews**（带即失去资格） | promoted 分支不挂任何自定义视图 |
| 8 | `setSmallIcon` 必须有 | `R.drawable.ic_notify` |

### 4. 通知通道：`fluid_cloud_task_v2`（含 user-lock 教训）

```kotlin
// OsPlusApplication.kt
val channel = NotificationChannel(
    CHANNEL_FLUID,                       // "fluid_cloud_task_v2"
    "实时任务流体云",
    NotificationManager.IMPORTANCE_HIGH,
).apply {
    lockscreenVisibility = Notification.VISIBILITY_PUBLIC   // 锁屏可见
    setShowBadge(false)
    enableVibration(false); setSound(null, null)            // 常驻卡片，静音免打扰
}
manager.createNotificationChannel(channel)
```

**user-lock 教训（重要）**：通知通道的优先级一旦被用户在通知栏里手动降级，
`mImportanceExplanation=user`，**代码无法恢复**——创建同 id 通道的任何修改都被系统忽略。
唯一出路是**换新通道 id**（`fluid_cloud_task_v1 → v2`），旧通道保留注册避免升级残留异常。
给常驻状态卡选通道 id 时一开始就要考虑「用户可能点静音」这个现实。

### 5. MetricStyle：六项指标 + 状态栏芯片

`MetricStyle` 是 Android 16+ 的标准指标样式（API 经 javap 对 android-37.jar 核实）：

```kotlin
val style = Notification.MetricStyle()
    .addMetric(Notification.Metric(Notification.Metric.FixedFloat(fps, "FPS"), "帧率"))
    .addMetric(Notification.Metric(Notification.Metric.FixedInt(s.cpuLoad.roundToInt(), "%"), "CPU 占用"))
    .addMetric(Notification.Metric(Notification.Metric.FixedText(fmtFreq(s.cpuFreqMhz)), "CPU 频率"))
    .addMetric(Notification.Metric(Notification.Metric.FixedInt(s.gpuLoad, "%"), "GPU 占用"))
    .addMetric(Notification.Metric(Notification.Metric.FixedText(fmtFreq(s.gpuMhz)), "GPU 频率"))
    .addMetric(Notification.Metric(Notification.Metric.FixedText(memText), "内存 可用/全部"))
    .setCriticalMetric(0)                        // 帧率是关键指标，折叠态优先展示

return builder
    .setContentTitle(if (recording) "OSPlus 正在记录" else "OSPlus 实时状态")
    .setStyle(style)
    .setRequestPromotedOngoing(true)
    .setShortCriticalText("%.0fFPS".format(fps)) // 状态栏芯片常驻显示帧率（≤7 字符全显）
    .addAction(toggleAction)
    .build()
```

- `Metric` 的值类型：`FixedInt(v, unit)` / `FixedFloat(v, unit)` / `FixedText(cs, unit)`；
- `setCriticalMetric(index)` 指定折叠态优先展示的指标；
- `setShortCriticalText` 直接控制**状态栏上的芯片文案**——这就是「用实时任务通知代替
  悬浮窗读帧率」的落点：任何应用全屏时，状态栏上始终挂着当前帧率。

### 6. 通知上的记录开关

```kotlin
val toggleIntent = PendingIntent.getService(
    this, 1,
    Intent(this, FpsOverlayService::class.java).setAction(ACTION_TOGGLE_RECORDING),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
)
val toggleAction = Notification.Action.Builder(
    Icon.createWithResource(this, R.drawable.ic_notify),
    if (recording) "停止记录" else "开始记录",
    toggleIntent,
).build()
```

链路：点按钮 → `onStartCommand` 收到 `ACTION_TOGGLE_RECORDING`
（`"com.osplus.tools.action.TOGGLE_FPS_RECORDING"`）→ `FpsRecorder.toggleRecording()` →
`updateNotification()` → 标题与按钮文案随录制状态翻转。录制状态存在 `FpsRecorder`
单例的 `StateFlow` 里，应用内开关、通知按钮读写同一份状态，不会出现两边显示撕裂。

### 7. Android 12–15 回退：RemoteViews 凝光卡片

Android 16 以下没有 Live Updates，回退为自定义玻璃卡片 + `DecoratedCustomViewStyle`
（系统会把自定义视图包进标准通知外壳）：

```kotlin
val views = RemoteViews(packageName, R.layout.notification_liquid_card)
views.setImageViewBitmap(R.id.notif_glass_bg, glassBitmap())   // 预渲染玻璃材质
applyNotificationText(views)                                    // 两行实时数值
return builder
    .setCustomContentView(views)
    .setCustomBigContentView(views)
    .setStyle(Notification.DecoratedCustomViewStyle())
    .build()
```

- **RemoteViews 不支持自定义 View / Canvas / Shader**——玻璃质感无法在通知里实时画，
  所以在 App 进程里预渲染成一张 Bitmap 塞进 `ImageView`（`fitXY` 铺底），
  两行 `TextView`（第一行 CPU+GPU、第二行内存）叠在图上。
- **`glassBitmap()` 四层 LinearGradient 预渲染**：

| 层 | 方向 | 色值 | 作用 |
|---|---|---|---|
| ① 玻璃体 | 纵向 | `#E62A3750 → #D8131826` | 半透明深色玻璃的厚度 |
| ② 凝光 | 斜向（左上→右下） | `#40FFFFFF → #14FFFFFF → #00000000 → #0FFFFFFF`（stops 0/0.35/0.62/1） | 光源在曲面玻璃上的反射 |
| ③ 顶缘 | 纵向 0→10dp | `#59FFFFFF → #00FFFFFF` | 勾出玻璃上缘受光边 |
| ④ 发丝描边 | 1dp STROKE | `#2EFFFFFF`（内缩 0.5dp） | 系统通知阴影里仍有清晰边界 |

- 尺寸固定（宽 `0.92×屏宽` 夹在 360–1080、高 74dp），风格不随采样变化 →
  `cachedGlass` 渲染一次反复使用，`onDestroy` 里 `recycle()`。
- **小图标必须用单色原生 `ic_notify`（纯白矢量）**：彩色 mipmap 会被状态栏渲染成白色色块。

### 8. 真机验证方法

```bash
adb shell dumpsys notification --noredact | grep -E "PROMOTED|shortCritical|importance|promoted"
```

通过判据（实测记录）：

- `flags` 含 `PROMOTED_ONGOING` ——系统确实把它提升成了实时任务卡片；
- `shortCriticalText=60FPS` ——状态栏芯片帧率在刷新；
- `importance=4 (HIGH)`、`explanation=app` ——通道未被 user-lock；
- 通知上点「开始记录」→ 标题变「OSPlus 正在记录」、按钮变「停止记录」→ 再点停止。

### 9. 已知限制

1. Live Updates 仅 **Android 16+**；12–15 走 RemoteViews 凝光卡片，样式与交互均正常
   但没有抽屉置顶与状态栏芯片。
2. promoted 通知的视觉由系统模板决定，**不能自定义布局**——这是官方硬限制，
   想要玻璃观感只能在低版本分支做。
3. 通知每秒 `notify()` 刷新一次，系统会合并不频繁的重复通知；帧率芯片更新粒度 1 秒。
4. 跨应用**帧率采样**仍依赖本进程能收到 vsync：前台服务保活下正常；若进程被系统冻结
   采样会停止（与旧悬浮窗方案一致，前台服务已尽量规避）。
5. 悬浮窗整套代码（`addOverlay` / `applyAlpha` / 手势仲裁）保留未删：
   窗口 `alpha` 恒为 1.0、视觉透明度走 `View.alpha` 的 Android 12「不受信任触摸」教训
   记录在代码注释里，回滚时直接调用即可。

---

## 第三部分：实时任务通知的可配置化

### 1. 两种呈现方式，一个开关

「设置 → 实时任务通知」的开关决定跨应用实时数据**怎么呈现**，两种方式互斥：

| 开关 | 呈现方式 | 依赖 |
|---|---|---|
| **开**（默认） | 系统实时任务通知：通知抽屉置顶卡片 + 状态栏芯片常驻 | 无（不需要悬浮窗权限） |
| **关** | `TYPE_APPLICATION_OVERLAY` 悬浮窗：轻点切换记录、长按/拖动移动位置 | `SYSTEM_ALERT_WINDOW` |

开关与「跨应用实时监视」（帧率页，控制前台服务是否运行）是两个正交的维度：
后者决定**采不采**，前者决定**怎么显示**。切换呈现方式时会顺带拉起服务，
否则用户改完开关看不到任何变化，会以为功能没生效。

关闭开关时若悬浮窗权限缺失，ViewModel **不切换状态**（否则会出现
「开关已关、通知却还在」的错位），由设置页直接把用户送到系统授权页。

### 2. 显示项：9 选 3

通知 / 悬浮窗显示的指标项由用户勾选，**最多 3 项、至少 1 项**。上限来自平台，
下限来自 `MetricStyle` 不接受空样式（`Builder.build()` 会判为非法）。

| 项 | key | 通知标签 | 值类型与单位 | 数据来源 | 默认 |
|---|---|---|---|---|---|
| 帧率 | `fps` | 帧率 | `FixedFloat` · FPS | `FpsRecorder.sample.fps` | **开** |
| CPU 占用 | `cpu_load` | CPU 占用 | `FixedInt` · % | `/proc/stat` 增量 | **开** |
| CPU 频率 | `cpu_freq` | CPU 频率 | `FixedText` · GHz/MHz | 各核平均，`cpufreq` sysfs | 关 |
| GPU 占用 | `gpu_load` | GPU 占用 | `FixedInt` · % | `kgsl-3d0/gpu_busy_percentage` | **开** |
| GPU 频率 | `gpu_freq` | GPU 频率 | `FixedText` · GHz/MHz | `kgsl-3d0/clock_mhz` | 关 |
| 内存占用 | `mem_used` | 内存占用 | `FixedInt` · % | `/proc/meminfo` 推导 | 关 |
| 实时功耗 | `power` | 实时功耗 | `FixedText` · mW | `charge_counter` 6 秒滑窗求导 | 关 |
| 电池温度 | `battery_temp` | 电池温度 | `FixedFloat` · ℃ | `power_supply/battery/temp` | 关 |
| 充电速度 | `charge_speed` | 充电速度 | `FixedText` · W | 电压 × 电流，仅在接入充电器时给值 | 关 |

**默认值的选取依据**是「瞬时变化、且不依赖 root 就能读到」——帧率、CPU 占用、
GPU 占用三项。频率类变化较慢；功耗 / 温度 / 充电速度依赖厂商 sysfs，
无 root 时读不到，一律显示 `--`（「读不到」与「就是 0」是两回事，
后者会让人误以为显卡闲着、电池不热）。

排列顺序**固定按枚举声明顺序**，与勾选先后无关——否则同一组选择会因为点击顺序
不同而每次刷新都换位置。达到数量边界时把不能再动的项置灰，而不是让点击静默失败。

### 3. 三个必须记牢的约束

**(1) 展开态最多 3 项指标——平台硬限制。**
官方《创建指标样式通知》写明「支持衡量最多 3 项指标」，
`Notification.MetricStyle` 的 API 文档为「shows up to 3 metrics when expanded」。
超出的项被系统**静默丢弃**（不报错），只表现为「勾了却没显示」，极难排查，
因此选择侧就收敛到 3 项，并在 `buildPromotedNotification` 里再 `take(3)` 兜一次。

**(2) `MetricStyle` 的引入级别是 API 37，不是 36。**
SDK 的 `api-versions.xml` 记为 `since="37.0"`，`Build.VERSION_CODES.CINNAMON_BUN = 37`；
promoted 相关的 `setRequestPromotedOngoing` / `POST_PROMOTED_NOTIFICATIONS` 是 `36.1`，
`setShortCriticalText` 是 `36`。
原先用 `BAKLAVA`(36) 做判断，在 **Android 16 上会引用不存在的类**
（`NoClassDefFoundError`，直接崩前台服务）。现改为 `CINNAMON_BUN`(37)，
并把整个提升分支包在 `runCatching` 里——任一步失败都退回 RemoteViews 卡片而不是崩溃。

**(3) promoted 通知不得携带 `customContentView`。**
带 RemoteViews 会直接失去实时任务资格。因此通知分支只有两条路：
API 37+ 走纯 `MetricStyle`（不挂任何自定义视图），其余走 RemoteViews 凝光卡片。

**(4) 两个分支绝不能复用同一个 `Notification.Builder`。**
`Builder` 是可变的。若提升分支在 `build()` 抛异常前已经写入
`setStyle(MetricStyle)` / `setRequestPromotedOngoing(true)`，回退分支再往同一个
Builder 上挂 RemoteViews，就会产出「既声明提升、又带自定义视图」的通知——
正好撞上第 (3) 条禁令，**系统直接丢弃它**。
真机症状很有迷惑性：日志显示通知每秒都在发，但 `dumpsys notification` 里
**没有这条 NotificationRecord**，`PROMOTED_ONGOING` 也一并消失。
修法是抽出 `baseNotificationBuilder()`，两个分支各 `new` 一个。

**(5) 回退不能静默。**
`runCatching { ... }.getOrNull()` 会把异常吞掉，于是「卡片没被提升」在界面上
完全看不出原因，只能靠 dumpsys 反推。必须 `Log.w(TAG, ...)` 留下痕迹
（TAG = `OSPlusNotif`）。

### 4. 真机实测结论（PJZ110 / Android 17 / versionCode 14）

| 项 | 结果 |
|---|---|
| 通知记录 | `importance=3`，`channel=fluid_cloud_task_v2` |
| 提升标志 | `flags=…\|PROMOTED_ONGOING` |
| 抽屉置顶卡片 | 正常显示所选指标数值 |
| 状态栏芯片 | `60FPS`（带单位；≤7 字符才会完整显示） |
| 刷新节拍 | 相邻两次 notify 间隔 **1.005~1.010 秒**，稳定 1 Hz |

### 5. 通知类别的历史遗留与清理

通知通道**一旦创建就常驻**：应用不再注册、不再往它发通知，系统也不会删除它，
它会一直留在「设置 → 通知 → 类别」里。本应用因此攒下两条空壳，其中一条与
现用通道**同名**「实时任务流体云」，让用户在类别列表里无法判断该关哪一条。

`OsPlusApplication.createChannel()` 现在每次启动主动
`deleteNotificationChannel` 清理 `fluid_cloud_task`（初版）与 `osplus_fps`（1.x 悬浮窗）。

注意 `deleteNotificationChannel` 是**软删除**：`dumpsys` 里仍能看到
`mDeleted=true` 的记录，但系统设置页会隐藏它们，用户侧达到目的。
若将来回滚到旧版本又往这些 id 发通知，系统会按默认配置自动重建，不会丢通知。

**通道被关（`mImportance=0`）时，前台服务通知仍会被发布，但系统直接丢弃它**
（`dumpsys` 里查不到记录）。排查「通知没出现」时先看类别是否被关。

### 6. 通知文案的长度预算（为什么不能用「CPU 占用」这类完整名称）

系统 `MetricStyle` **把卡片横向空间均分给每个指标**，字号与列宽都由系统模板决定，
**应用无法调整字号**。展开态还会把单位拼到标签后（`内存占用` + `%` → `内存占用 (%)`），
超宽即被直接截断——实测「内存占用 (%)」被右边缘切掉，单位读不出来。

因此 `NotifMetric` 把标签拆成两个：

| 用途 | 字段 | 取值示例 |
|---|---|---|
| 设置页 / RemoteViews 卡片（宽度充裕） | `label` | `CPU 占用`、`内存占用` |
| `MetricStyle` 通知（宽度受限） | `notifLabel` | `CPU`、`内存` |

按「约 7 个全角字符」估算预算：`帧率 (FPS)`、`内存 (%)`、`功耗 (mW)` 均可完整显示。

### 7. 后台刷新会中断——ColorOS 会冻结整个进程（重要架构约束）

**通知模式下的后台实时刷新在 ColorOS 上做不到。** 应用退到后台约 5~10 秒后，
系统用内核 cgroup freezer 冻结整个进程，冻结期间进程得不到任何 CPU，
采样协程与通知刷新协程全部停摆。

真机实测（PJZ110 / Android 17，同一时刻同步采样）：

```
T+3s  CPU=565  cgroup.freeze=0   ← 仍在运行
T+6s  CPU=585  cgroup.freeze=1   ← 已被冻结
T+30s CPU=585  cgroup.freeze=1   ← 30 秒 CPU 增量 0，持续冻结
```

| 场景 | 结果 |
|---|---|
| 通知模式后台 | `cgroup.freeze=1`；10 秒 CPU 增量 **0**、上下文切换增量 **0** |
| 切回前台 | 6 秒 CPU 增量 **329** |
| **悬浮窗模式后台** | CPU **持续增长**（约 +12 tick/s），全程 `freeze=0` |

#### 7.1 执行冻结的是 ColorOS 的 Hans 框架，不是 AOSP

日志实据（system_server）：

```
OplusHansManager : sp_FRZ uid=10551, pkg=com.sukisu.ultra, frozen by super freeze
OplusBinderProxy : proxyBinder uid: 10548 pkg: com.osplus.tools calling: OFreezer
Osense-BaseDecisionMaker: scene: SCENE_APP_SWITCH, excutingPolicy: freezer
OplusHansFreezeManager: get oplus_freeze service
```

binder 服务：`oplus_freeze: [com.oplus.app.IOplusHansFreezeManager]`。

**因此 AOSP 的旋钮对它无效**：实测把
`settings put global cached_apps_freezer disabled` 设成关闭后仍被冻结；
`dumpsys activity processes` 里该进程也始终 `isFrozen=false`、
`curProcState=4`（FOREGROUND_SERVICE）——ColorOS 走自己的速冻通道，AMS 字段不反映。

#### 7.2 root 也解决不了，这不是权限问题

1. **冻结是内核层面的**：`cgroup.freeze=1` 后进程执行不了任何代码，包括 root 代码。
   「被冻住后再跑 root 命令自救」在物理上不成立。
2. **调 AOSP 开关无效**（见 7.1），所以「用 root 关掉系统冻结开关」在 ColorOS 上不成立。
3. **把采集搬到 root 守护进程也救不了通知**：root 进程可以持续采样，
   但**通知只能由应用进程发布**——应用被冻住时既读不到数据也发不出通知。
   这条路能保住「数据不丢档」，保不住「通知持续刷新」。

理论上 root 能做的只有「持续调用 `oplus_freeze` 给自己解冻」或「关掉整个 Hans 框架」，
前者要与每次场景切换后的重新冻结打拉锯战（需在另一个未被冻结的 root 进程里循环执行），
后者是系统级改动、影响所有应用——两者都不划算。

#### 7.3 解法：「保活锚点」——靠「可见」，不靠「权限」

冻结框架的目标是**后台应用**；持有可见窗口的进程不属于后台，自然不在目标集合里。
据此实现的方案是 **1×1 不可见悬浮窗（保活锚点）**：

```kotlin
WindowManager.LayoutParams(
    1, 1, TYPE_APPLICATION_OVERLAY,
    FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE or FLAG_NOT_TOUCH_MODAL,
    PixelFormat.TRANSLUCENT,
).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 0; alpha = 1f }
```

三条实现约束：

- **窗口 alpha 必须是 1.0**：0 会被判定为「不可见」，锚点就白挂了
  （与「悬浮窗不能用窗口 alpha 表达半透明」是同一类约束，原因相反）；
- **`FLAG_NOT_TOUCHABLE`**：绝不拦截任何触摸；
- 需要 `SYSTEM_ALERT_WINDOW`，未授权时静默跳过、功能自动降级。

**实测（PJZ110 / Android 17，三次独立复现，每次 60 秒后台）**：

| 指标 | 结果 |
|---|---|
| `cgroup.freeze` | 全程 **0** |
| CPU 时间增量 | 持续增长（如 696→1222），约 +9~12 tick/s |
| 后台通知刷新 | 97 条 / 60 秒，稳定 1 秒一次 |
| 通知提升状态 | 保持 `PROMOTED_ONGOING` |
| 窗口几何 | `(0,0)(1x1) ty=APPLICATION_OVERLAY`，`isVisibleRequested()=true` |

对照：无锚点时 `T+6s` 即 `freeze=1`、CPU 增量归零。

**息屏后仍会被冻结**（实测 `freeze=1`）：屏幕关闭后窗口不再「可见」，
这是平台行为、锚点无法覆盖。影响有限——息屏时没人看通知，
亮屏后进程解冻、通知立即恢复刷新（实测 PID 不变、无崩溃）。

#### 7.4 代价与取舍

- 通知模式若要后台保活，**仍然需要悬浮窗权限**——「通知模式免权限」这个卖点
  与「后台持续刷新」在 ColorOS 上无法兼得，用户需二选一（设置页两个开关都给了）。
- 锚点让进程常驻后台运行，会持续消耗少量 CPU（实测约 10% 单核），这是保活的必然代价。

#### 7.5 一个必须记住的坑：读流协程的异常会绕过 `runCatching` 打崩应用

给 `Shell.run()` 加超时时，最初写成：

```kotlin
val stdout = async(Dispatchers.IO) { process.inputStream.bufferedReader().readText() }
...
if (!process.waitFor(TIMEOUT, MILLISECONDS)) { process.destroyForcibly(); return@runCatching ... }
```

超时分支 `destroyForcibly()` 关掉管道后，挂起的 `readText()` 抛 IOException。
它作为 **`async` 子协程**失败，会沿 Job 层级取消父协程、
**绕过外层的 `runCatching`**，最终冒到采样循环之外——真机复现了一次
`APP CRASH(EXCEPTION)`，堆栈停在 `Shell$run$2$1$stdout$1.invokeSuspend`。

**修法**：读流体自己吞掉异常，让子协程永不失败。

```kotlin
val stdout = async(Dispatchers.IO) {
    runCatching { process.inputStream.bufferedReader().readText() }.getOrDefault("")
}
```

**并且**在采样循环外包一层 `runCatching`——单次采样失败绝不该终结整个循环，
否则界面上只是数字不再变化，用户只能靠重启应用恢复。

### 8. `Shell.run()` 必须有超时——否则 `su` 一挂住，整个监控永久停摆

真机实测：进程下挂着一条 `su -c <采样脚本>` 存活 **1 分 33 秒**，
而 `run()` 内部是阻塞式 `waitFor()`，于是采样循环永远停在原地、**不会自愈**。
外部表现与「进程被冻结」几乎一样（CPU 增量归零、通知不再刷新），
但前台服务仍在、也没有崩溃日志，极难从表象定位。

修法（`core/Shell.kt`）：

```kotlin
private const val COMMAND_TIMEOUT_MS = 5_000L

val stdout = async(Dispatchers.IO) { process.inputStream.bufferedReader().readText() }
val stderr = async(Dispatchers.IO) { process.errorStream.bufferedReader().readText() }
if (!process.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
    process.destroyForcibly()
    return@runCatching CommandResult(false, "", "命令超时（${COMMAND_TIMEOUT_MS}ms），已强制结束", -1)
}
```

两点都必须做：

- **超时 + 强杀**：保证最坏情况只是本轮读数缺失，而不是整个监控停摆。
- **stdout / stderr 并发读**：原实现「先把 stdout 读到 EOF、再读 stderr」，
  在另一个管道被写满（约 64 KB）时会互相死锁——父进程等 stdout 关闭，
  子进程等 stderr 被读走。

`writeNode()` 里的 `Runtime.exec(su)` + `waitFor()` 同样加了超时。

### 9. 充电速度的取数口径

`current_now` 的单位因内核而异（mA 或 µA），且**节点名不携带单位**，
只能按数量级判定：小于 20 mA（= 20000 µA）时按 mA 解释并乘 1000。

更关键的是**只在确实接入充电器时给值**——放电时 `current_now` 同样非零，
不做接入判断会把整机功耗误报成「充电速度」。接入状态取自
`BatteryDataSource` 的快照（每 5 秒刷新），因此刚插上充电器时读数最多滞后 5 秒。

`mV × µA / 1e9 = W`。

### 4. 三处显示的刷新节拍统一为 1 秒

抽屉置顶卡片、状态栏芯片（`setShortCriticalText`）与 `TYPE_APPLICATION_OVERLAY`
悬浮窗共用**同一个 1 秒节拍**（`FpsOverlayService.REFRESH_INTERVAL_MS`），
与 ViewModel 的采样间隔一致，因此显示与数据同频。

**不能直接订阅数据流**：帧率来自 `FpsRecorder`（Choreographer 逐帧统计），
系统指标来自 `LiveMetrics`（每秒采样），是两条各自 1 秒的独立流。
`combine` 它们会在同一秒内触发两次重建——通知被 `notify()` 两次、
悬浮窗文字被改写两次，既浪费，也让「刷新频率」变成一个不可控的约数。

改用显式节拍后，频率是确定的 1 Hz；用户动作（切换呈现方式、
点通知按钮切换记录）仍会立即调用一次 `refreshLiveReadouts()`，不必等下一个节拍。

---

## 第四部分：ZRAM 容量调整的安全闸门

### 1. 为什么需要闸门

ZRAM 容量调整会走 `swapoff → reset → disksize → mkswap → swapon` **重建交换分区**，
期间正在使用交换区的应用会短暂卡顿，且改错容量不易回滚。
因此默认**上锁**（`zram_resize_enabled` 默认 `false`），由用户显式开启后才解锁控件。

### 2. 三层约束

| 层 | 行为 |
|---|---|
| 界面 | 未开启时容量滑块与「应用并重建 / 取当前值」整体 `enabled = false`，显示为灰色 |
| ViewModel | `resizeZram()` 入口再判一次闸门，未开启直接 return |
| 有效条件 | `rootAvailable && zramResizeEnabled`——两个条件缺一不可 |

「UI 置灰 + VM 拒绝」是双保险：界面状态可能被别处改写，
只在 UI 层挡无法保证「未开启就绝不会真的重建交换分区」。

### 3. 容量范围从 0 开始

滑块范围 **0 ~ 8 GB**。`0` 不是「0 字节的分区」，而是**关闭该 zram 交换设备**：

```
swapoff <dev>; echo 1 > reset; echo 0 > disksize      # 不做 mkswap / swapon
```

0 字节的设备无法格式化，`swapon` 必然失败，因此 0 走单独分支，
只关交换并清零容量。

---

*文档对应版本：OSPlus 2.0.0（versionCode 14）。*
