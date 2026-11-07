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

# Firebase messaging (service instantiated by the framework)
-keep class com.google.firebase.** { *; }
-keep class com.minichat.app.notifications.** { *; }

# Hilt generated code
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
