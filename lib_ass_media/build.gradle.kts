plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.peerless2012.exo"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.annotation.experimental)
    implementation(libs.androidx.media3.exo)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.effect)

    // The atlas API is introduced by this patch and must be compiled from the
    // matching local module rather than the older published beta artifact.
    implementation(project(":lib_ass_kt"))

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
