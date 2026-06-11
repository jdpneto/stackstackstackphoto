pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "8.7.3"
        id("com.android.library")     version "8.7.3"
        kotlin("android")             version "2.0.21"
        kotlin("jvm")                 version "2.0.21"
        kotlin("plugin.serialization") version "2.0.21"
        id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    }
}

rootProject.name = "stack-stack-stack-android"
include(":stackengine")
include(":app")
