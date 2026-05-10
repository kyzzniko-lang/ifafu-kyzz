# General
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# Models (serialized by Gson)
-keep class com.ifafu.kyzz.data.model.** { *; }
-keep class com.ifafu.kyzz.ui.settings.UpdateChecker$ReleaseInfo { *; }
-keep class com.ifafu.kyzz.ui.settings.UpdateChecker$ReleaseInfo$Asset { *; }

# Hilt
-dontwarn dagger.hilt.**
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Jsoup
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# AndroidX / Lifecycle
-keep class * extends androidx.lifecycle.ViewModel { <init>(...); }
-keep class * extends androidx.lifecycle.AndroidViewModel { <init>(...); }

# Keep BuildConfig (used for API keys)
-keep class com.ifafu.kyzz.BuildConfig { *; }
