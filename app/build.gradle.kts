plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsKotlinAndroid)
    alias(libs.plugins.compose.compiler)

}

kotlin {
    jvmToolchain(17)
}

android {
    namespace = "com.example.mlkitwithjetpackcompose"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.mlkitwithjetpackcompose"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        // Prevent compression of ML models to allow memory-mapping (mmap)
        noCompress.addAll(listOf(".tflite", ".lite"))
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.mlkit.vision)

    // Add these to fix the crash:
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.object1.detection.custom)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    implementation(libs.play.services.location)
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    implementation(libs.text.recognition)
    implementation(libs.play.services.mlkit.text.recognition)
    implementation(libs.coil.compose)
    implementation(libs.play.services.mlkit.document.scanner)
    implementation(libs.navigation.compose)
    implementation(project(":openCV"))
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.object1.detection)
}