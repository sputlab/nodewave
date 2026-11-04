plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.nodewave.mesh"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nodewave.mesh"
        minSdk = 29
        targetSdk = 35
        versionCode = 11
        versionName = "0.8.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        unitTests.isReturnDefaultValues = true // android.util.Log is a no-op in JVM tests
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    // QR codes for verifying contacts in person: ZXing (Apache-2.0) and CameraX (Apache-2.0), no Google Play Services
    implementation(libs.zxing.core)
    // Offline-capable map view (Apache-2.0, no Google services); tiles come from files or from a server the user chooses
    implementation(libs.osmdroid)
    // Offline vector maps (Mapsforge .map files, LGPL-3.0, GPL-3.0 compatible)
    implementation(libs.osmdroid.mapsforge)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
