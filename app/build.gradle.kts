import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("androidx.baselineprofile")
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
val musiconeTrace = providers.gradleProperty("musiconeTrace").map(String::toBoolean).getOrElse(false)

android {
    namespace = "com.musicone.demo"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.shuyunr.musicone"
        minSdk = 23
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        manifestPlaceholders["musiconeTrace"] = musiconeTrace.toString()
        vectorDrawables.useSupportLibrary = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

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
    }

    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

dependencies {
    // 仅显式追踪构建携带函数级采样支持，正常正式包不打包 Perfetto 二进制。
    if (musiconeTrace) {
        implementation("androidx.compose.runtime:runtime-tracing:1.11.4")
        implementation("androidx.tracing:tracing-perfetto:1.0.1")
        implementation("androidx.tracing:tracing-perfetto-binary:1.0.1")
    }
    implementation(project(":usb-audio-driver"))
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
    implementation("androidx.media3:media3-session:1.10.1")
    // 非应用商店安装也能在首次运行后把内置 Profile 交给 ART。
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("com.google.zxing:core:3.5.3")

    debugImplementation("androidx.compose.ui:ui-tooling:1.11.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
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
