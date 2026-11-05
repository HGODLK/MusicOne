import java.util.Properties

plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose") }

// 测试直接核对Android实际构建版本，避免入口与清单一起遗漏仍误判一致。
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    systemProperty("musicone.kugou.versionCode", requireNotNull(android.defaultConfig.versionCode))
    systemProperty("musicone.kugou.versionName", requireNotNull(android.defaultConfig.versionName))
}

// 发布身份从本机私有配置读取，换机器构建也不使用另一把默认调试密钥。
val sourceSigningFile = file(providers.gradleProperty("sourceSigningProperties")
    .getOrElse(rootProject.file("keystore/source-signing.properties").absolutePath))
val sourceSigning = Properties().apply {
    if (sourceSigningFile.isFile) sourceSigningFile.inputStream().use { load(it) }
}
val validateSourceSigning = tasks.register("validateSourceSigning") {
    doLast {
        check(sourceSigningFile.isFile) { "缺少音源签名配置，请设置 sourceSigningProperties 或 keystore/source-signing.properties" }
        listOf("storeFile", "storePassword", "keyAlias", "keyPassword").forEach {
            check(!sourceSigning.getProperty(it).isNullOrBlank()) { "音源签名配置缺少 $it" }
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(validateSourceSigning) }

android {
    namespace = "com.musicone.source.kugou"
    compileSdk = 37
    // 私有资源使用独立包 ID；保留宿主 0x7f 给共享 Compose/AndroidX 资源，兼容 API 23。
    androidResources { additionalParameters += listOf("--package-id", "0x71", "--allow-reserved-package-id") }
    defaultConfig {
        applicationId = "com.musicone.source.kugou"
        minSdk = 23; targetSdk = 37; versionCode = 2; versionName = "1.1"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true }
    sourceSets.getByName("test").resources.srcDir("src/main/assets")
    // 共用登录展示随各音源独立编译，不增加宿主 SDK 运行接口。
    sourceSets.getByName("main").kotlin.directories += "../login-ui/src/main/java"
    testOptions { unitTests.isReturnDefaultValues = true }
    signingConfigs.getByName("debug") { enableV1Signing = true; enableV2Signing = true }
    signingConfigs.create("sourceRelease") {
        sourceSigning.getProperty("storeFile")?.let { storeFile = sourceSigningFile.parentFile.resolve(it) }
        storePassword = sourceSigning.getProperty("storePassword")
        keyAlias = sourceSigning.getProperty("keyAlias")
        keyPassword = sourceSigning.getProperty("keyPassword")
        enableV1Signing = true; enableV2Signing = true
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("sourceRelease")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "source-rules.pro")
        }
    }
}
dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
    if (findProject(":source-sdk") != null) compileOnly(project(":source-sdk"))
    else compileOnly(files(providers.gradleProperty("sourceSdk").get()))
    compileOnly("androidx.activity:activity-compose:1.13.0")
    compileOnly("androidx.compose.runtime:runtime:1.11.4")
    compileOnly("androidx.compose.ui:ui:1.11.4")
    compileOnly("androidx.compose.foundation:foundation:1.11.4")
    compileOnly("androidx.compose.animation:animation:1.11.4")
    compileOnly("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    compileOnly("androidx.compose.material3:material3:1.4.0")
    compileOnly("androidx.compose.material:material-icons-extended:1.7.8")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.google.zxing:core:3.5.3")
    if (findProject(":source-sdk") != null) testImplementation(project(":source-sdk"))
    else testImplementation(files(providers.gradleProperty("sourceSdk").get()))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testImplementation("androidx.compose.runtime:runtime:1.11.4")
}
configurations.matching { it.name.endsWith("RuntimeClasspath") && !it.name.contains("UnitTest", ignoreCase = true) }.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
}
tasks.register<Sync>("packageDebugSource") {
    dependsOn("assembleDebug")
    from(layout.buildDirectory.file("outputs/apk/debug/kugou-debug.apk"))
    into(layout.buildDirectory.dir("outputs/source-packs/debug"))
    rename { "kugou-1.1.mscm" }
}
tasks.register<Sync>("packageReleaseSource") {
    dependsOn("assembleRelease")
    from(layout.buildDirectory.file("outputs/apk/release/kugou-release.apk"))
    into(layout.buildDirectory.dir("outputs/source-packs/release"))
    rename { "kugou-1.1.mscm" }
}

