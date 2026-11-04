# Add project specific ProGuard rules here.
# By default, the flags in this file are applied together with the defaults
# in the Android SDK proguard-android-optimize.txt.

# WebRTC — keep all JNI-referenced classes
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Kotlin coroutines
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
