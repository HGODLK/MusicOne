-keep class org.example.source.ExampleSource { *; }
-keep class org.example.source.R$* { *; }
# 包内实现使用独有混淆空间，避免父优先加载时与宿主短类名冲突。
-repackageclasses org.example.supplemental.internal
-dontwarn androidx.**
-dontwarn kotlin.**
