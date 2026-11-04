pluginManagement {
    plugins { id("com.android.application") version "9.2.1"; id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" }
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "bilibili"
