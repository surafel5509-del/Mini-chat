# ---------------------------------------------------------------------------
# MINI CHAT — R8 rules
# ---------------------------------------------------------------------------

# kotlinx.serialization (Supabase DTOs + realtime payloads)
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.minichat.app.**$$serializer { *; }
-keepclassmembers class com.minichat.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.minichat.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Supabase + Ktor
-keep class io.github.jan.supabase.** { *; }
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
-dontwarn kotlinx.coroutines.debug.**

# Services and receivers are instantiated by the framework, not by us, so R8 must
# not rename or strip them. RealtimeNotificationService is declared in the
# manifest.
-keep class com.minichat.app.notifications.** { *; }

# MediaRecorder/ExifInterface are platform/AndroidX classes reached reflectively
# from native code paths.
-dontwarn androidx.exifinterface.**
-keep class androidx.exifinterface.** { *; }

# OkHttp / Okio (transitive from Ktor, used directly for the otp-guard call)
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Hilt generated code
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
