@file:Suppress("UnstableApiUsage")

import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }

    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.4.10"
        id("org.jetbrains.intellij.platform") version "2.18.1"
        id("org.jetbrains.intellij.platform.settings") version "2.18.1"
        id("org.jetbrains.changelog") version "2.5.0"
        id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    }
}

plugins {
    id("org.jetbrains.intellij.platform.settings")
    id("org.gradle.toolchains.foojay-resolver-convention")
}

rootProject.name = "contextual-bookmarks-plugin"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
    }
}
