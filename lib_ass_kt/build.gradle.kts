plugins {
    alias(libs.plugins.android.library)
}

val usePrebuiltLibass = providers.gradleProperty("libassUsePrebuilt")
    .map(String::toBooleanStrict)
    .getOrElse(
        System.getProperty("os.name").contains("Windows", ignoreCase = true) || gradle.parent != null
    )
val prebuiltLibassAar = rootProject.layout.projectDirectory.file("OUTPUT/lib_ass-release.aar").asFile

android {
    namespace = "io.github.peerless2012.ass.kt"
    compileSdk = 36
    ndkVersion = providers.gradleProperty("androidNdkVersion").get()

    defaultConfig {
        minSdk = 21
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "VERSION_NAME", "\"${providers.gradleProperty("VERSION_NAME").get()}\"")
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    buildFeatures {
        buildConfig = true
        prefab = true
    }

    packaging {
        jniLibs {
            excludes += setOf("**/libass.so", "**/libc++_shared.so")
        }
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
}

dependencies {
    if (usePrebuiltLibass) {
        check(prebuiltLibassAar.isFile) {
            "Missing ${prebuiltLibassAar.absolutePath}. Run rebuild-libass-wsl.bat before building."
        }
        compileOnly(files(prebuiltLibassAar))
    } else {
        implementation(project(":lib_ass"))
    }
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
