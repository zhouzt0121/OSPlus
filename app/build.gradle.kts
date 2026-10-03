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
        // versionCode 递增以便覆盖安装已发布的 1.2.0（code 4）
        versionCode = 16
        versionName = "2.5.0"
    }

    buildFeatures {
        compose = true
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

    // Shizuku：以 shell(uid 2000) 身份执行命令的替代提权通道。
    //
    // 引入它的意义在于覆盖「能解锁 BL / 有 root 能力但不想或不能装 Magisk」的设备：
    // Shizuku 走的是系统 adb 调试通道，可读 /sys、/proc、跑 dumpsys 与 am，
    // 足以支撑 CPU 频率控制、进程管理、GPU 与内存读取。
    // 但它拿不到 root，因此 /data/adb 下的 Magisk 模块文件仍不可读
    // （见 PrivilegeCapabilities.canControlPerfSched）。
    //
    // provider 必须一并引入：只加 api 的话 ShizukuProvider 不存在，
    // 清单里声明的组件会在启动时抛 ClassNotFoundException。
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // BouncyCastle：仅用于生成 ADB 客户端身份的自签 X509 证书。
    //
    // 为什么不用 JDK 自带的 sun.security.x509：Android 上没有那个包。
    // 也不用 Conscrypt（它在 Android 上不暴露证书签发 API）。
    // 只需要 bcpkix（证书构建）+ bcprov（ASN.1 与算法），两个加起来约 8MB，
    // 会被 R8 裁到实际用到的部分。
    implementation("org.bouncycastle:bcpkix-jdk18on:1.80.2")
    implementation("org.bouncycastle:bcprov-jdk18on:1.80.2")
    // Conscrypt：ADB 无线调试配对必须能取 TLS 导出的密钥材料（exportKeyingMaterial）。
    //
    // 配对协议的设计是「先用 TLS 1.3 客户端证书认证，再从 TLS 会话导出 64 字节
    // 密钥材料，把它拼在 6 位配对码后面当 SPAKE2 口令」。JDK 的 SSLSocket 没有
    // 暴露 exporter（SSLSession 上没有对应方法），只有 Conscrypt 提供了
    // Conscrypt.exportKeyingMaterial(socket, label, context, length)。
    //
    // 必须用捆绑版而不是系统的实现：
    //   * Android 10 以下系统根本没有 Conscrypt 的 exporter 扩展；
    //   * 部分 ROM 的 Conscrypt 版本过旧或被裁剪，反射探测会失败。
    // 捆绑它会随 APK 带上约 1.2 MB 的原生库，换取「所有 Android 11+ 设备都能配对」。
    //
    // 注意：R8 会删掉 Conscrypt 的原生绑定入口，所以 proguard-rules.pro 里
    // 必须保留 org.conscrypt.。
    //
    // 版本下限是 2.7.0：2.5.2 的 libconscrypt_jni.so 是按 4 KB 页编译的
    // （实测 PT_LOAD 对齐 = 4096, 4096），而 Android 15+ 已强制 16 KB 内存页，
    // 在 Android 17 真机上系统会弹出「ELF 文件对齐检查失败」的兼容性警告。
    // 2.7.0 的同一文件对齐为 16384, 16384, 16384，合规。
    // 升级前请用 ELF program header 的 p_align 复核，不要只看版本号。
    implementation("org.conscrypt:conscrypt-android:2.7.0")
}
