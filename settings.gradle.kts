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
dependencyResolutionManagement {

    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://zebratech.jfrog.io/artifactory/EMDK-Android/")
        }
        maven {
            url = uri("https://jitpack.io")
        }
    }

    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "HardwareSDK"
include(":app")
include(":hardwareSDK")
