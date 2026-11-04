import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.util.Properties

plugins {
    id("com.android.application") version "9.2.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21"
}

// 单一配置同时生成入口常量和已签名清单，避免重复维护身份。
val sourceProperties = Properties().apply {
    rootProject.file("source.properties").reader(Charsets.UTF_8).use { load(it) }
}
fun quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
val sourceId = sourceProperties.getProperty("sourceId")
val sourceName = sourceProperties.getProperty("sourceName")
val packageVersion = providers.gradleProperty("sourceVersion").map(String::toInt)
    .getOrElse(sourceProperties.getProperty("sourceVersion").toInt())
val packageVersionName = sourceProperties.getProperty("sourceVersionName") + ".$packageVersion"
require(packageVersion > 0) { "sourceVersion 必须为正整数" }
abstract class GenerateSourceManifest : DefaultTask() {
    @get:Input abstract val manifestJson: Property<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @TaskAction fun generate() {
        outputDirectory.get().asFile.apply { mkdirs() }.resolve("musicone-source.json")
            .writeText(manifestJson.get(), Charsets.UTF_8)
    }
}
val generateSourceManifest = tasks.register<GenerateSourceManifest>("generateSourceManifest") {
    manifestJson.set("""
        {"id":${quoted(sourceId)},"name":${quoted(sourceName)},"kind":"SUPPLEMENTAL","versionCode":$packageVersion,"versionName":${quoted(packageVersionName)},"entryClass":"org.example.simple.SimpleExampleSource","apiVersion":1,"minHostVersionCode":4,"minHostVersionName":"0.3.0-麦辣鸡腿堡","minAndroid":23,"kotlinVersion":"2.3.21","composeVersion":"1.11.4","capabilities":["HOME","CONTENT","CHILD_ITEMS","IMPORT","PLAYBACK"],"useCustomPages":false}
    """.trimIndent())
    outputDirectory.set(layout.buildDirectory.dir("generated/source-manifest/assets"))
}
android {
    namespace = "org.example.simple"
    compileSdk = 37
    // 私有资源使用独立包 ID；保留宿主 0x7f 给共享 Compose/AndroidX 资源，兼容 API 23。
    androidResources { additionalParameters += listOf("--package-id", "0x76", "--allow-reserved-package-id") }
    defaultConfig {
        applicationId = "org.example.musicone.simple"
        minSdk = 23
        targetSdk = 37
        versionCode = packageVersion
        versionName = packageVersionName
        buildConfigField("String", "SOURCE_ID", quoted(sourceId))
        buildConfigField("String", "SOURCE_NAME", quoted(sourceName))
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
