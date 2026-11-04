plugins { id("com.android.library"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "com.musicone.source.runtime"
    compileSdk = 37
    defaultConfig { minSdk = 23; consumerProguardFiles("consumer-rules.pro") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    api(project(":source-sdk"))
    implementation("androidx.compose.runtime:runtime:1.11.4")
    implementation("androidx.compose.ui:ui:1.11.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
