pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() }; plugins {
    id("com.android.application") version "9.2.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21"
} }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "MusicOneSubsonicSource"
