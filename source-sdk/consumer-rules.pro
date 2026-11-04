# SDK 使用 jvmDefault=DISABLE；普通 $DefaultImpls 类及静态方法同属公开二进制接口。
# AGP 9 不向 desugar 合成 $-CC 传播 keep 信息，不能依靠接口 keep 留住默认实现。
-keep class com.musicone.source.sdk.** { *; }

# 外部代码独立编译，宿主必须保留约定共享运行库的二进制名称和成员。
# 优化宿主先将共享库预 desugar 为 classfile，让这些规则也覆盖已物化的 $-CC 类及方法。
# 保留全部二进制入口及名称，同时允许方法体优化，避免关闭整个 Compose/协程运行库的优化。
-keep,allowoptimization class kotlin.** { *; }
-keep,allowoptimization class kotlinx.coroutines.** { *; }
-keep,allowoptimization class androidx.compose.** { *; }
-keep,allowoptimization class androidx.collection.** { *; }
-keep,allowoptimization class androidx.lifecycle.** { *; }
-keep,allowoptimization class androidx.savedstate.** { *; }
-keep,allowoptimization class androidx.core.** { *; }
-keep,allowoptimization class androidx.activity.** { *; }
-keep,allowoptimization class androidx.annotation.** { *; }
-keep,allowoptimization class androidx.customview.poolingcontainer.** { *; }
-keep,allowoptimization class androidx.emoji2.** { *; }
-keep,allowoptimization class androidx.tracing.** { *; }
-keep,allowoptimization class androidx.graphics.** { *; }
