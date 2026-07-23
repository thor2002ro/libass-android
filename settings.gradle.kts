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

if (!file("local.properties").exists() &&
    System.getenv("ANDROID_HOME").isNullOrBlank() &&
    System.getenv("ANDROID_SDK_ROOT").isNullOrBlank()
) {
    System.getenv("LOCALAPPDATA")
        ?.let { file("$it/Android/Sdk") }
        ?.takeIf { it.isDirectory }
        ?.let { file("local.properties").writeText("sdk.dir=${it.invariantSeparatorsPath}\n") }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "libass-android"
include(":lib_ass")
include(":lib_ass_kt")
include(":lib_ass_media")
