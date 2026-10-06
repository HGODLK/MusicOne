plugins {
    id("com.android.library")
}

android {
    namespace = "com.decent.usbaudio"
    compileSdk = 37

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild { cmake { cppFlags("") } }
    }

    ndkVersion = "27.1.12297006"

    externalNativeBuild {
        cmake {
            path("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin { jvmToolchain(17) }
}
