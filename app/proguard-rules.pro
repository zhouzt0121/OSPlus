# Compose / Kotlin 元数据
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontwarn kotlinx.coroutines.**
-dontwarn org.jetbrains.annotations.**
# Miuix 依赖 Compose Runtime 的反射能力
-keep class top.yukonga.miuix.** { *; }
-keepclassmembers class ** {
    @androidx.compose.runtime.Composable <methods>;
}
# Shizuku：provider 与 Binder 接口靠反射和组件名解析，混淆会直接导致授权失败
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**
-keep class moe.shizuku.** { *; }

# Conscrypt：内部用 JNI 按类名查找原生绑定，混淆后会直接报 UnsatisfiedLinkError。
# NativeCrypto 及其子类必须保留，否则 exportKeyingMaterial 不可用。
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**
