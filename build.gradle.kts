plugins {
    // AGP 9 起内置 Kotlin 支持，无需再单独声明 kotlin-android 插件
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
