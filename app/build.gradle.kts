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
        versionCode = 2
        versionName = "1.1.0"
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

    debugImplementation("androidx.compose.ui:ui-tooling")
}
