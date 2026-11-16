pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Aperture's CameraX build (lineage-next/camerax-aperture), same pin as upstream Aperture
        maven("https://raw.githubusercontent.com/lineage-next/camerax-aperture/55b687c1232294e908ef6f46e004a58e57a0e63d/.m2") {
            content {
                includeGroupByRegex("androidx\\.camera.*")
            }
        }
        google()
        mavenCentral()
        maven("https://jitpack.io") {
            content {
                includeGroup("com.github.bogerchan")
            }
        }
    }
}

rootProject.name = "Shadowgram"
include(":app")
include(":tdlib")
include(":RustCore")
include(":aperture")
 