plugins {
    alias(libs.plugins.android.library)
}

val libassPatchDir = rootProject.layout.projectDirectory.dir("patches/libass")
val libassPatchFiles = libassPatchDir.asFileTree.matching {
    include("*.patch")
}
val libassSourceDir = layout.projectDirectory.dir("src/main/cpp/libass-cmake/src/ass")
val libassCmakePatchDir = rootProject.layout.projectDirectory.dir("patches/libass-cmake")
val libassCmakePatchFiles = libassCmakePatchDir.asFileTree.matching {
    include("*.patch")
}
val libassCmakeSourceDir = layout.projectDirectory.dir("src/main/cpp/libass-cmake")

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

val applyLibassCmakePatches = tasks.register("applyLibassCmakePatches") {
    group = "build setup"
    description = "Applies wrapper-owned patches to the vendored libass-cmake build files."
    inputs.files(libassCmakePatchFiles)

    doLast {
        val patchFiles = libassCmakePatchFiles.files.sortedBy { it.name }
        val sourceDir = libassCmakeSourceDir.asFile
        check(patchFiles.isNotEmpty()) {
            "Missing libass-cmake patches in ${libassCmakePatchDir.asFile.absolutePath}"
        }
        check(sourceDir.isDirectory) { "Missing libass-cmake source: ${sourceDir.absolutePath}" }

        fun git(vararg args: String, ignoreExit: Boolean = false): Int =
            providers.exec {
                workingDir = sourceDir
                commandLine("git", "-c", "core.autocrlf=false", *args)
                isIgnoreExitValue = ignoreExit
            }.result.get().exitValue

        for (patchFile in patchFiles) {
            if (git("apply", "--reverse", "--check", "--ignore-space-change", patchFile.absolutePath, ignoreExit = true) == 0) {
                continue
            }
            if (git("apply", "--check", "--ignore-space-change", patchFile.absolutePath, ignoreExit = true) != 0) {
                throw org.gradle.api.GradleException(
                    "Cannot apply libass-cmake patch ${patchFile.name}. Reset the tracked build files, then rerun Gradle."
                )
            }
            git("apply", "--ignore-space-change", patchFile.absolutePath)
        }
    }
}

tasks.configureEach {
    if (name.startsWith("configureCMake") || name.startsWith("buildCMake")) {
        dependsOn(applyLibassPatches, applyLibassCmakePatches)
    }
}

android {
    namespace = "io.github.peerless2012.ass"
    compileSdk = 36
    ndkVersion = providers.gradleProperty("androidNdkVersion").get()

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
