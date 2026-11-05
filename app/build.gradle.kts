import java.util.Properties
import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("androidx.baselineprofile")
}

abstract class GenerateMigrationStorageTestSource : DefaultTask() {
    @get:InputFile abstract val migrationSource: RegularFileProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @TaskAction fun generate() {
        val original = migrationSource.get().asFile.readText()
        val declaration = "package com.musicone.sourceintegration"
        check(original.startsWith(declaration)) { "真实迁移源文件包名已变化，请同步测试接线" }
        // 只改测试副本的包名，算法始终来自同一生产文件，避免优化宿主同名DEX冲突。
        outputDirectory.get().file("com/musicone/validation/production/LegacySourceDataMigration.kt").asFile.apply {
            parentFile.mkdirs()
            writeText(original.replaceFirst(declaration, "package com.musicone.validation.production"))
        }
    }
}
val generateMigrationStorageTestSource = tasks.register<GenerateMigrationStorageTestSource>("generateMigrationStorageTestSource") {
    migrationSource.set(layout.projectDirectory.file("src/main/java/com/musicone/sourceintegration/LegacySourceDataMigration.kt"))
    outputDirectory.set(layout.buildDirectory.dir("generated/migrationStorageTest/kotlin"))
}
tasks.matching { it.name.startsWith("compile") && it.name.endsWith("AndroidTestKotlin") }.configureEach {
    dependsOn(generateMigrationStorageTestSource)
}

// 共享接口先预 desugar 为普通 classfile，避免 AGP 9 在 R8 中丢弃外部包所需 companion。
val sharedDesugarCompiler = File(Class.forName("com.android.tools.r8.D8", false,
    com.android.build.gradle.AppPlugin::class.java.classLoader).protectionDomain.codeSource.location.toURI())
androidComponents.onVariants { variant ->
    if (variant.buildType == "debugRelease" || variant.buildType == "release") {
        val task = tasks.register<com.musicone.build.SharedInterfaceDesugarTask>("${variant.name}SharedInterfaceDesugar") {
            bootClasspath.from(androidComponents.sdkComponents.bootClasspath)
            compilerClasspath.from(sharedDesugarCompiler)
            workers.set(gradle.startParameter.maxWorkerCount)
            output.set(layout.buildDirectory.file("intermediates/sharedInterfaceDesugar/${variant.name}/classes.jar"))
        }
        variant.artifacts.forScope(com.android.build.api.variant.ScopedArtifacts.Scope.ALL).use(task).toTransform(
            com.android.build.api.artifact.ScopedArtifact.CLASSES,
            com.musicone.build.SharedInterfaceDesugarTask::getAllJars,
            com.musicone.build.SharedInterfaceDesugarTask::getAllDirectories,
            com.musicone.build.SharedInterfaceDesugarTask::getOutput)
        if (variant.buildType == "release") {
            val profileCompatibility = tasks.register<com.musicone.build.ProfileNamespaceCompatibilityTask>("generateReleaseCompatibleProfiles") {
                originalProfiles.from(layout.projectDirectory.dir("src/release/generated/baselineProfiles"))
                compiledClasses.from(task.flatMap { it.output })
                outputDirectory.set(layout.buildDirectory.dir("generated/compatibleProfiles/release"))
            }
            variant.sources.baselineProfiles?.addGeneratedSourceDirectory(profileCompatibility,
                com.musicone.build.ProfileNamespaceCompatibilityTask::getOutputDirectory)
        }
    }
}

baselineProfile {
    variants {
        create("release") {
            // 正式包显式生成，避免每次普通构建都启动模拟器采集。
            automaticGenerationDuringBuild = false
            mergeIntoMain = false
            saveInSrc = true
            from(project(":baselineprofile"))
        }
    }
}

val releaseSigningProperties = Properties().apply {
    val propertiesFile = rootProject.file("keystore.properties")
    if (propertiesFile.isFile) {
        propertiesFile.inputStream().use(::load)
    }
}
val hasReleaseSigning = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .all { !releaseSigningProperties.getProperty(it).isNullOrBlank() }
// 实验能力可独立构建对照，正式包默认启用资源预备与关闭态持续刷新。
val musiconeAdpfHints = providers.gradleProperty("musiconeAdpfHints").map(String::toBoolean).getOrElse(false)
val musiconeLyricResourcePrepare = providers.gradleProperty("musiconeLyricResourcePrepare").map(String::toBoolean).getOrElse(true)
// 默认采用用户验收通过的持续刷新方案，按句更新仍可用于构建回退。
val musiconeResidentLyricRefresh = providers.gradleProperty("musiconeResidentLyricRefresh").map(String::toBoolean).getOrElse(true)
val musiconeTrace = providers.gradleProperty("musiconeTrace").map(String::toBoolean).getOrElse(false)
// 同一源码可关闭歌词宿主驱动做实机对照，原帧率设置及视觉效果不变。
val musiconeLyricFrameDrive = providers.gradleProperty("musiconeLyricFrameDrive").map(String::toBoolean).getOrElse(true)

android {
    namespace = "com.musicone"
    compileSdk = 37
    // 与原生模块统一 NDK，确保宿主打包时能找到剥离调试符号的工具。
    ndkVersion = "27.1.12297006"

    defaultConfig {
        applicationId = "io.github.shuyunr.musicone"
        minSdk = 23
        targetSdk = 37
        buildConfigField("boolean", "MUSICONE_ADPF_HINTS", musiconeAdpfHints.toString())
        buildConfigField("boolean", "MUSICONE_LYRIC_FRAME_DRIVE", musiconeLyricFrameDrive.toString())
        buildConfigField("boolean", "MUSICONE_TRACE", musiconeTrace.toString())
        buildConfigField("boolean", "MUSICONE_LYRIC_RESOURCE_PREPARE", musiconeLyricResourcePrepare.toString())
        buildConfigField("boolean", "MUSICONE_RESIDENT_LYRIC_REFRESH", musiconeResidentLyricRefresh.toString())
        versionCode = 5
        versionName = "0.3.1-过桥米线"
        manifestPlaceholders["musiconeTrace"] = musiconeTrace.toString()
        vectorDrawables.useSupportLibrary = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true; buildConfig = true }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseSigningProperties.getProperty("storeFile"))
                storePassword = releaseSigningProperties.getProperty("storePassword")
                keyAlias = releaseSigningProperties.getProperty("keyAlias")
                keyPassword = releaseSigningProperties.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        // 本地性能测试包：启用发布优化，使用调试签名覆盖现有测试安装。
        create("debugRelease") {
            initWith(getByName("release"))
            applicationIdSuffix = ".debugrelease"
            versionNameSuffix = "-debugrelease"
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            matchingFallbacks += "release"
        }
        // 与优化实验包共用应用身份及调试签名，用于同身份升级和外部加载对照。
        create("sourceDebug") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".debugrelease"
            versionNameSuffix = "-source-debug"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "debug"
        }
    }

    sourceSets {
        getByName("androidTest") {
            kotlin.directories += layout.buildDirectory.dir("generated/migrationStorageTest/kotlin").get().asFile.absolutePath
        }
        getByName("sourceDebug") {
            kotlin.directories += "src/sourcePackValidation/java"
            manifest.srcFile("src/sourcePackValidation/AndroidManifest.xml")
        }
        getByName("debugRelease") {
            kotlin.directories += "src/sourcePackValidation/java"
            manifest.srcFile("src/sourcePackValidation/AndroidManifest.xml")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    // 测试 APK 与实验宿主使用同一签名，可在旧版实验安装上建立迁移数据。
    testBuildType = providers.gradleProperty("musiconeTestBuildType").getOrElse("debug")

    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

// 首次启动分发的仍是独立签名音源包；宿主不链接平台模块的类或资源。
listOf("debug", "sourceDebug", "debugRelease", "release").forEach { variant ->
    val optimized = variant == "debugRelease" || variant == "release"
    val packageVariant = if (optimized) "release" else "debug"
    val packageTask = if (optimized) "packageReleaseSource" else "packageDebugSource"
    val taskSuffix = variant.replaceFirstChar(Char::uppercaseChar)
    val assetsDirectory = layout.buildDirectory.dir("generated/sourcePackAssets/$variant")
    val prepare = tasks.register<Sync>("prepare${taskSuffix}SourcePacks") {
        listOf("qq", "kugou", "netease", "bilibili", "subsonic").forEach { source ->
            dependsOn(":source-packs:$source:$packageTask")
            from(rootProject.layout.projectDirectory.dir("source-packs/$source/build/outputs/source-packs/$packageVariant")) {
                include("*.mscm", "*.mscp")
                into("source-packs")
            }
        }
        into(assetsDirectory)
    }
    android.sourceSets.getByName(variant).assets.srcDir(assetsDirectory.get().asFile)
    // AGP 9 的传统 sourceSet 不接受任务 Provider；所有实际读取该目录的构建步骤显式等待生成任务。
    val assetConsumers = setOf("merge${taskSuffix}Assets", "generate${taskSuffix}LintVitalReportModel",
        "generate${taskSuffix}LintReportModel", "lintVitalAnalyze${taskSuffix}", "lintAnalyze${taskSuffix}")
    tasks.matching { it.name in assetConsumers }.configureEach { dependsOn(prepare) }
}

dependencies {
    implementation(project(":third-party:torph"))
    // 与独立音源编译契约一致，避免传递依赖选择旧协程 ABI。
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // 仅显式追踪构建携带函数级采样支持，正常正式包不打包 Perfetto 二进制。
    if (musiconeTrace) {
        implementation("androidx.compose.runtime:runtime-tracing:1.11.4")
        implementation("androidx.tracing:tracing-perfetto:1.0.1")
        implementation("androidx.tracing:tracing-perfetto-binary:1.0.1")
    }
    implementation(project(":usb-audio-driver"))
    implementation(project(":native-audio"))
    implementation(project(":source-runtime"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.compose.animation:animation:1.11.4")
    implementation("androidx.compose.foundation:foundation:1.11.4")
    implementation("androidx.compose.ui:ui:1.11.4")
    implementation("androidx.compose.ui:ui-tooling-preview:1.11.4")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.10.1")
    implementation("androidx.media3:media3-session:1.10.1")
    // 非应用商店安装也能在首次运行后把内置 Profile 交给 ART。
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("com.google.zxing:core:3.5.3")
    // 聊天正文复用成熟 Markdown 渲染器，流式更新与表格由独立 AI 组件承接。
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")

    debugImplementation("androidx.compose.ui:ui-tooling:1.11.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestCompileOnly("com.google.errorprone:error_prone_annotations:2.28.0")
}

// 与参考工程相同：中文路径下的测试类先打包，避免 Windows 测试进程无法加载散落的 class 文件。
val debugUnitTestClassesJar = tasks.register<Jar>("debugUnitTestClassesJar") {
    dependsOn("compileDebugUnitTestKotlin")
    archiveFileName.set("music-motion-tests.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    destinationDirectory.set(layout.dir(providers.provider {
        file(System.getProperty("java.io.tmpdir")).resolve("music-motion-test-jars")
    }))
    from(layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"))
    from(layout.buildDirectory.dir("intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes"))
}

tasks.withType<Test>().configureEach {
    if (name == "testDebugUnitTest") {
        dependsOn(debugUnitTestClassesJar)
        doFirst { classpath = files(debugUnitTestClassesJar.get().archiveFile) + classpath }
    }
}
