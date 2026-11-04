pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MusicOneAndroid"
include(":app")
include(":third-party:torph")
include(":baselineprofile")
include(":usb-audio-driver")
include(":native-audio")
include(":source-sdk")
include(":source-runtime")
include(":source-packs:bilibili")
include(":source-packs:qq")
include(":source-packs:kugou")
include(":source-packs:netease")
