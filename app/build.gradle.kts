plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.osplus.tools"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.osplus.tools"
        minSdk = 33
        targetSdk = 36
        // 1.3.0：信息架构改版（底栏四页 + 设置移出导航 + 统一顶栏 + 电源页提升为一级页）
        // 1.4.0：概览页指标改回圆环（CPU / GPU / 内存），电池卡换成帧率折线卡
        // 1.5.0：新增「性能调度」二级页，接管 Uperf Game Turbo 与 A-SOUL Games Optimization 两个模块
        // 1.5.1：全应用 SwitchRow 改用官方 LiquidToggle；LiquidToggle 修复「只能拖不能点」（加 pressable + didDrag）；
        //        6 处 miuix Slider 改用官方 LiquidSlider
        // 1.5.2：底栏加横向拖动切换（点击 + 拖动统一手势；选中胶囊跟随手指）
        // 2.0.0：实时任务通知（可配置显示项 + 保活锚点）、ZRAM 调整闸门、Shell 超时与并发读流
        // 2.1.0：新增「耗电统计」录制页（真实时间轴电量曲线 + 平均功耗/理论续航 + 按应用拆分）；
        //        修复电池节点被 SELinux 静默跳过、容量单位多除 1000、通知复制无回执；
        //        清理死代码（无引用的组件、不可达的 24 小时估算链路）
        // 2.5.0：按钮与分段条全面换装原版 Kyant0 组件——
        //        删除 LiquidGlassButton 适配层，全部按钮直接引用原版 LiquidButton；
        //        顶栏按钮 48dp → 36dp 正圆（原版新增 height / horizontalPadding 参数）；
        //        LiquidNavTabs 内部换成原版 LiquidBottomTabs（13 处调用点零改动）；
        //        ChoiceChip 换原版并调淡未选中灰；修复动作组与提权方式「点击后胶囊不跟随」
        // 2.6.0：对照 Scene5 Alpha 补能力缺口——
        //        新增「系统开关」页（settings get/put 类开关：显示点按操作 / 指针位置 /
        //        强制 GPU 渲染 / 自由窗口 / 强制可调整大小 / 网络 ADB；动画速度三档；
        //        隐藏状态栏图标多选）；
        //        新增「提权管理」页（Root 授权状态、能力速查、探测明细、重新探测）；
        //        概览页接线 SystemExtrasDataSource（负载 / 网络 / IO / 磁盘）；
        //        提权相关三块组件提取为共享件，设置页与提权页共用；
        //        版本号改读 BuildConfig，不再写死
        //        （注：早期注释里提过的「温控配置」页从未落地，此处予以更正）
        // 2.6.1：移除 Shizuku / ADB 两条非 Root 提权通道（实测在本应用需求下全部失效），
        //        只保留 Root；UI 组件实验室页；输入框组件与 ADB 解耦（AdbInputField → NumberInputField）
        // 2.6.2 / 2.6.3 / 2.6.4：系统开关页与提权管理页的迭代（开关项增删、探测明细）
        // 2.6.5：系统级监控数据补齐——
        //        CPU 温度语义修正（不再拿 thermal_zone0 当结温，改按 type 语义打分选 cpuss）；
        //        ZRAM 列举改用 shell glob（File.listFiles() 在厂商 sysfs 上恒 null）；
        //        ZRAM 解析放宽列数判据（缺 mem_used_total 时不再连容量一起丢）；
        //        删除「界面行为」卡片（settings 写得进去但 ColorOS 不响应，「能拨但没效果」）；
        //        删除「充电控制」（内核节点普遍不可写，功能整体不可用）；
        //        三级页面返回改走路由栈（原来单个 route 变量会被三级覆盖二级，返回跳级）
        // 2.7.0：修复帧率记录「重启后历史丢失」（四处独立缺陷叠加）——
        //        ① SysFpsDataSource 探测脚本用 `[ -e ]` 判存在性，在 SELinux
        //           拒绝父目录遍历时恒假，可用 sysfs 节点被误判为不存在，
        //           落到已失效的 SurfaceFlinger 兜底并写入垃圾帧率（实测 119.9/7.1/1.5）；
        //        ② startFpsRecording 先开记录开关再异步建会话，抢跑的采样
        //           因 activeSessionId 尚为 -1 被丢弃（实测 9.6 秒只落 5 条）；
        //        ③ 兜底通道加物理合理性闸门（≤200 FPS 且帧间隔 ≥5ms），
        //           不可信值返回「不可读」而非把垃圾数写进图表与数据库；
        //        ④ FpsScreen 图表区只判内存 `records.isEmpty()`，重启后打开历史
        //           会话（viewingSamples 有数据但 records 为空）整个图表区被判成
        //           空状态、曲线不渲染——改为统一下游数据集 `src`（历史优先）
        //        仓库瘦身：移除 dist/（167 个第三方 Magisk 模块文件）、历史 APK 与调试截图
        // 2.8.0：预测性返回（Predictive Back）接入——
        //        ① 层级重构：一级页提升为**常驻底层**，二级/三级页改为盖在其上的
        //           全屏浮层。改造前两级页面共用同一个 AnimatedContent 插槽，
        //           手势让开之后底下没有任何内容，接预测性返回会直接露底；
        //        ② 新增 PredictiveBack.kt：从 NavigationEventTransitionState.InProgress
        //           取出手势进度（NavigationBackHandler 本身只给完成/取消两个终态），
        //           手势中吃实时值保证零延迟跟手，结束后由 Animatable 平滑收尾；
        //        ③ 浮层跟手右移（屏幕宽 32%）+ 圆角渐显 + 缩放 0.94 + 轻微压暗，
        //           底栏随浮层让开同步淡入；
        //        ④ 移除 2.7.0 遗留的实测结论：`AnimatedContent` 单插槽方案
        versionCode = 25
        versionName = "2.8.0"
    }

    buildFeatures {
        compose = true
        // 设置页的「版本」一行读 BuildConfig.VERSION_NAME。
        // 写死版本号在每次发版后都会与实际包体不一致，用户报障时给的是错信息。
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // Android 15+ 强制 16 KB 内存页。除了 ELF 内部的 p_align，
            // 打包时 .so 在 APK 内的存储偏移也必须按 16 KB 对齐，
            // 否则真机会报「ELF 文件对齐检查失败 / 未知错误」。
            // useLegacyPackaging=false 让 AGP 走 zipalign -P 16 的页对齐路径。
            useLegacyPackaging = false
        }
    }

    signingConfigs {
        // 个人设备自用：使用本机 debug 证书签名 release 包，便于直接安装
        create("localSign") {
            storeFile = file("C:/Users/Holmes/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
        }
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("localSign")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-process:2.11.0")

    // 预测性返回手势支持
    implementation("androidx.navigationevent:navigationevent-compose:1.1.2")

    // Miuix UI（HyperOS 风格组件）
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    // 模糊 / 液态玻璃（需要 minSdk 33）
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.4")

    // Kyant0 官方液态玻璃库（AndroidLiquidGlass / Backdrop）。
    //
    // 之所以换到它而不是继续用 miuix-blur：官方组件
    // （LiquidBottomTabs / LiquidButton / LiquidToggle / LiquidSlider）
    // 用的就是这套 API，而 miuix-blur 缺了其中三块——
    // 没有 effects 包（vibrancy）、没有 shadow 包（Shadow / InnerShadow）、
    // Highlight 的预置样式也不一样。
    // 缺了这些就只能自己用 Compose 画，而自绘层与 drawBackdrop 的渲染几何对不上，
    // 之前底栏中间那条白色横带就是这么来的。
    implementation("io.github.kyant0:backdrop:2.0.1")
    implementation("io.github.kyant0:capsule:2.1.3")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
