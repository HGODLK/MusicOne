plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}
kotlin {
    compilerOptions {
        // 默认实现作为普通 DefaultImpls 类公开，避免 AGP 9 丢弃外部包所需的 desugar $-CC。
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.DISABLE)
    }
}
android {
    namespace = "com.musicone.source.sdk"
    compileSdk = 37
    defaultConfig { minSdk = 23; consumerProguardFiles("consumer-rules.pro") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true }
}
dependencies {
    compileOnly("androidx.compose.runtime:runtime:1.11.4")
    compileOnly("androidx.compose.ui:ui:1.11.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.compose.runtime:runtime:1.11.4")
}
