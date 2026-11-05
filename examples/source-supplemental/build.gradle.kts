import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

plugins {
    id("com.android.application") version "9.2.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21"
}

abstract class GenerateSourceManifest : DefaultTask() {
    @get:Input abstract val sourceVersion: Property<Int>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @TaskAction fun generate() {
        outputDirectory.get().asFile.apply { mkdirs() }.resolve("musicone-source.json").writeText("""
        {"id":"org.example.supplemental","name":"外部音源示例 supplemental","kind":"SUPPLEMENTAL","versionCode":${sourceVersion.get()},"versionName":"1.0.${sourceVersion.get()}","entryClass":"org.example.source.ExampleSource","apiVersion":1,"minHostVersionCode":5,"minHostVersionName":"0.3.1-过桥米线","minAndroid":23,"kotlinVersion":"2.3.21","composeVersion":"1.11.4","capabilities":["HOME"],"useCustomPages":true,"presentation":{"pageColorArgb":4281559415}}
        """.trimIndent())
    }
}

val packageVersion = providers.gradleProperty("sourceVersion").map(String::toInt).getOrElse(2)
require(packageVersion > 0) { "sourceVersion 必须为正整数" }
val failInitialization = providers.gradleProperty("failInitialization").map(String::toBoolean).getOrElse(false)
val failContent = providers.gradleProperty("failContent").map(String::toBoolean).getOrElse(false)
val generateSourceManifest = tasks.register<GenerateSourceManifest>("generateSourceManifest") {
    sourceVersion.set(packageVersion)
    outputDirectory.set(layout.buildDirectory.dir("generated/source-manifest/assets"))
}
android {
    namespace = "org.example.source"
    compileSdk = 37
    // 私有资源使用独立包 ID；保留宿主 0x7f 给共享 Compose/AndroidX 资源，兼容 API 23。
    androidResources { additionalParameters += listOf("--package-id", "0x75", "--allow-reserved-package-id") }
    defaultConfig {
        applicationId = "org.example.musicone.source"
        minSdk = 23
        targetSdk = 37
        versionCode = packageVersion
        versionName = "1.0.$packageVersion"
        buildConfigField("boolean", "FAIL_INITIALIZATION", failInitialization.toString())
        buildConfigField("boolean", "FAIL_CONTENT", failContent.toString())
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true; buildConfig = true }
    signingConfigs.getByName("debug") { enableV1Signing = true; enableV2Signing = true }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "source-rules.pro")
        }
    }
}
androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(generateSourceManifest, GenerateSourceManifest::outputDirectory)
}
dependencies {
    // 与宿主共享 Kotlin 标准库，外部包不得携带另一份运行时。
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
    compileOnly(files(providers.gradleProperty("sourceSdk").getOrElse("../sdk/source-sdk.aar")))
    compileOnly("androidx.compose.runtime:runtime:1.11.4")
    compileOnly("androidx.compose.ui:ui:1.11.4")
    compileOnly("androidx.compose.foundation:foundation:1.11.4")
    compileOnly("androidx.compose.material3:material3:1.4.0")
}
