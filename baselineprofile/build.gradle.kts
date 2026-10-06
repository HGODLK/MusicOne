plugins {
    id("com.android.test")
    id("androidx.baselineprofile")
}

android {
    namespace = "com.musicone.demo.baselineprofile"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 同一次任务分别采集关键路径与精简启动路径，二者由插件合并到对应产物。
        testInstrumentationRunnerArguments["class"] =
            listOf(
                "com.musicone.demo.baselineprofile.BaselineProfileGenerator",
                "com.musicone.demo.baselineprofile.StartupProfileGenerator",
            ).joinToString(",")
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation("androidx.test.ext:junit:1.3.0")
    implementation("androidx.test.uiautomator:uiautomator:2.4.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.5.0-beta01")
}
