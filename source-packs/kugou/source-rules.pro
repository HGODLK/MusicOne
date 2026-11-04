-keep class com.musicone.source.kugou.KugouSourceEntry { *; }
-keep class com.musicone.source.kugou.SecurityCaptchaBridge { *; }
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
-dontwarn androidx.**
-dontwarn kotlinx.coroutines.**
-repackageclasses com.musicone.source.kugou.optimized

