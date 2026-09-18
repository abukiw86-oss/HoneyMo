# Proguard / R8 Configuration for HoneyMo Main App

# Keep Service, Receivers, Activities
-keep class com.example.HoneyMo.** { *; }
-keep class com.example.HoneyMo.service.** { *; }
-keep class com.example.HoneyMo.receiver.** { *; }
-keep class com.example.HoneyMo.facecam.** { *; }
-keep class com.example.HoneyMo.network.** { *; }
-keep class com.example.HoneyMo.util.** { *; }

# Jetpack Compose
-keepclassmembers class * {
    @androidx.compose.runtime.Composable *;
}

# Kotlin Coroutines
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }

# Hardware Camera & MediaCodec
-keep class android.hardware.Camera** { *; }
-keep class android.media.MediaCodec** { *; }
-keep class android.media.projection.** { *; }
