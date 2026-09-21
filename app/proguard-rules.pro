# Compose / Kotlin 元数据
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontwarn kotlinx.coroutines.**
-dontwarn org.jetbrains.annotations.**
# Miuix 依赖 Compose Runtime 的反射能力
-keep class top.yukonga.miuix.** { *; }
-keepclassmembers class ** {
    @androidx.compose.runtime.Composable <methods>;
}
