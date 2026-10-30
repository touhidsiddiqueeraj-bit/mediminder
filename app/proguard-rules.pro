# R8 is disabled for v1 (see app/build.gradle.kts). These rules are kept ready
# for when shrinking is turned on.

# SQLCipher (encrypted database)
-keep class net.zetetic.** { *; }
-dontwarn net.zetetic.**

# ML Kit on-device text recognition
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# Kotlinx serialization
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, AnnotationDefault
-keep,includedescriptorclasses class com.mediminder.app.**$$serializer { *; }
-keepclassmembers class com.mediminder.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.mediminder.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Retrofit
-keepattributes Exceptions
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions*
