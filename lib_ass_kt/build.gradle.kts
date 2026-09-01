plugins {
    alias(libs.plugins.android.library)
}

val isWindowsHost = System.getProperty("os.name").contains("Windows", ignoreCase = true)
val prebuiltLibassAar = rootProject.layout.projectDirectory.file("OUTPUT/lib_ass-release.aar").asFile

android {
    namespace = "io.github.peerless2012.ass.kt"
    compileSdk = 36
    ndkVersion = "28.1.13356709"

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
    if (isWindowsHost) {
        check(prebuiltLibassAar.isFile) {
            "Missing ${prebuiltLibassAar.absolutePath}. Run dependencies/libass-android/rebuild-libass-wsl.bat before building on Windows."
        }
        compileOnly(files(prebuiltLibassAar))
    } else {
        implementation(project(":lib_ass"))
    }
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
