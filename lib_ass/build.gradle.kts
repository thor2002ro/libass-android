plugins {
    alias(libs.plugins.android.library)
}

val libassPatchDir = rootProject.layout.projectDirectory.dir("patches/libass")
val libassPatchFiles = libassPatchDir.asFileTree.matching {
    include("*.patch")
}
val libassSourceDir = layout.projectDirectory.dir("src/main/cpp/sources/ass")
val libassPrefabHeadersDir = layout.buildDirectory.dir("generated/prefab-headers")

val applyLibassPatches = tasks.register("applyLibassPatches") {
    group = "build setup"
    description = "Applies main-repo libass patches to the vendored libass source."
    inputs.files(libassPatchFiles)

    doLast {
        val patchFiles = libassPatchFiles.files.sortedBy { it.name }
        val sourceDir = libassSourceDir.asFile
        check(patchFiles.isNotEmpty()) { "Missing libass patches in ${libassPatchDir.asFile.absolutePath}" }
        check(sourceDir.isDirectory) { "Missing libass source: ${sourceDir.absolutePath}" }

        fun git(
            vararg args: String,
            indexFile: File? = null,
            normalizeLineEndings: Boolean = true,
            ignoreExit: Boolean = false,
        ): Pair<Int, String> {
            val command = mutableListOf("git")
            if (normalizeLineEndings) {
                command += listOf("-c", "core.autocrlf=false")
            }
            command += args
            val execution = providers.exec {
                workingDir = sourceDir
                commandLine(command)
                if (indexFile != null) {
                    environment("GIT_INDEX_FILE", indexFile.absolutePath)
                }
                isIgnoreExitValue = ignoreExit
            }
            return execution.result.get().exitValue to execution.standardOutput.asText.get().trim()
        }

        val expectedIndex = temporaryDir.resolve("libass-expected.index")
        val actualIndex = temporaryDir.resolve("libass-actual.index")
        expectedIndex.delete()
        actualIndex.delete()

        git("read-tree", "HEAD", indexFile = expectedIndex)
        for (patchFile in patchFiles) {
            if (git("apply", "--cached", patchFile.absolutePath, indexFile = expectedIndex, ignoreExit = true).first != 0) {
                throw org.gradle.api.GradleException("Cannot construct the expected libass patch state at ${patchFile.name}.")
            }
        }
        val expectedTree = git("write-tree", indexFile = expectedIndex).second
        val headTree = git("rev-parse", "HEAD^{tree}").second
        val patchedPaths = git("diff-tree", "--no-commit-id", "--name-only", "-r", headTree, expectedTree).second
            .lineSequence()
            .filter(String::isNotBlank)
            .toList()

        fun actualTree(): String {
            actualIndex.delete()
            git("read-tree", "HEAD", indexFile = actualIndex)
            val (presentPaths, absentPaths) = patchedPaths.partition { sourceDir.resolve(it).exists() }
            if (presentPaths.isNotEmpty()) {
                git(
                    "add", "--", *presentPaths.toTypedArray(),
                    indexFile = actualIndex,
                    normalizeLineEndings = false,
                )
            }
            if (absentPaths.isNotEmpty()) {
                git(
                    "rm", "--cached", "--ignore-unmatch", "--", *absentPaths.toTypedArray(),
                    indexFile = actualIndex,
                )
            }
            return git("write-tree", indexFile = actualIndex).second
        }

        val initialTree = actualTree()
        if (initialTree == expectedTree) {
            return@doLast
        }
        if (initialTree != headTree) {
            throw org.gradle.api.GradleException(
                "The libass source has a stale or partial patch state. Reset " +
                    "the freshly fetched libass source, then rerun Gradle."
            )
        }

        for (patchFile in patchFiles) {
            if (git("apply", patchFile.absolutePath, ignoreExit = true).first != 0) {
                throw org.gradle.api.GradleException(
                    "Cannot apply libass patch ${patchFile.name}. Reset " +
                        "the freshly fetched libass source, then rerun Gradle."
                )
            }
        }
        if (actualTree() != expectedTree) {
            throw org.gradle.api.GradleException("Applied libass patches do not match the expected source tree.")
        }
    }
}
val prepareLibassPrefabHeaders = tasks.register<Sync>("prepareLibassPrefabHeaders") {
    dependsOn(applyLibassPatches)
    from(libassSourceDir.dir("libass")) {
        include("ass.h", "ass_types.h")
        into("ass")
    }
    into(libassPrefabHeadersDir)
}

tasks.configureEach {
    if (name.startsWith("configureCMake") || name.startsWith("buildCMake")) {
        dependsOn(applyLibassPatches)
    }
    if (name.startsWith("prefab") && name.endsWith("Package")) {
        dependsOn(prepareLibassPrefabHeaders)
    }
}

android {
    namespace = "io.github.peerless2012.ass"
    compileSdk = 36
    ndkVersion = providers.gradleProperty("androidNdkVersion").get()

    defaultConfig {
        minSdk = 21
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
            headers = libassPrefabHeadersDir.get().asFile.absolutePath
        }
    }
}
