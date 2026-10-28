# Media3 ExoPlayer rules
-keep class androidx.media3.exoplayer.** { *; }
-keep class androidx.media3.extractor.** { *; }
-dontwarn androidx.media3.**

# OkHttp rules
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Coil rules
-dontwarn coil.**
-keep class coil.** { *; }

# Kotlin Coroutines
-dontwarn kotlinx.coroutines.**

# Android TV & Leanback
-keep class androidx.leanback.** { *; }

# Model classes
-keepclassmembers class com.famelack.tv.data.model.** { *; }

