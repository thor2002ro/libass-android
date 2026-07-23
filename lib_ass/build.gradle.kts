plugins {
    alias(libs.plugins.android.library)
}

val libassPatchDir = rootProject.layout.projectDirectory.dir("patches/libass")
val libassPatchFiles = libassPatchDir.asFileTree.matching {
    include("*.patch")
}
val libassSourceDir = layout.projectDirectory.dir("src/main/cpp/libass-cmake/src/ass")

val applyLibassPatches = tasks.register("applyLibassPatches") {
    group = "build setup"
    description = "Applies main-repo libass patches to the vendored libass source."
    inputs.files(libassPatchFiles)

    doLast {
        val patchFiles = libassPatchFiles.files.sortedBy { it.name }
        val sourceDir = libassSourceDir.asFile
        check(patchFiles.isNotEmpty()) { "Missing libass patches in ${libassPatchDir.asFile.absolutePath}" }
        check(sourceDir.isDirectory) { "Missing libass source: ${sourceDir.absolutePath}" }

        fun git(vararg args: String, ignoreExit: Boolean = false): Int =
            providers.exec {
                workingDir = sourceDir
                commandLine("git", "-c", "core.autocrlf=false", *args)
                isIgnoreExitValue = ignoreExit
            }.result.get().exitValue

        if (git("apply", "--reverse", "--check", patchFiles.last().absolutePath, ignoreExit = true) == 0) {
            return@doLast
        }
        for (patchFile in patchFiles) {
            if (git("apply", "--reverse", "--check", patchFile.absolutePath, ignoreExit = true) == 0) {
                continue
            }
            if (git("apply", "--check", patchFile.absolutePath, ignoreExit = true) != 0) {
                throw org.gradle.api.GradleException(
                    "Cannot apply libass patch ${patchFile.name}. Reset " +
                        "lib_ass/src/main/cpp/libass-cmake/src/ass to the tracked submodule commit, then rerun Gradle."
                )
            }
            git("apply", patchFile.absolutePath)
        }
    }
}

tasks.configureEach {
    if (name.startsWith("configureCMake") || name.startsWith("buildCMake")) {
        dependsOn(applyLibassPatches)
    }
}

android {
    namespace = "io.github.peerless2012.ass"
    compileSdk = 36
    ndkVersion = "28.1.13356709"

    defaultConfig {
        minSdk = 21
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    buildFeatures {
        prefabPublishing = true
    }

    buildTypes {
        debug {
            externalNativeBuild {
                cmake {
                    cppFlags("-fno-omit-frame-pointer")
                }
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    prefab {
        create("ass") {
            headers = "src/main/cpp/include"
        }
    }
}

dependencies {
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
