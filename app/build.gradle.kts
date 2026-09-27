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
        // versionCode 递增以便覆盖安装已发布的 1.2.0（code 4）
        versionCode = 8
        versionName = "1.5.1"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
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
