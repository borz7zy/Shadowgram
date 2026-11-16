/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: Apache-2.0
 */

// Aperture's app module as a library: no applicationId, AOSP generatebp or own cargo build
// (camera_pipeline lives in :RustCore and ships in librustcore.so).
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.lineageos.aperture"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        lintConfig = file("lint.xml")
    }
}

dependencies {
    implementation(project(":RustCore"))

    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    api(libs.androidx.camera.core)
    api(libs.androidx.camera.camera2)
    api(libs.androidx.camera.lifecycle)
    api(libs.androidx.camera.video)
    api(libs.androidx.camera.view)
    implementation(libs.androidx.camera.viewfinder.core)
    implementation(libs.androidx.camera.extensions)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.recyclerview)
    implementation(libs.material)
    implementation(libs.coil)
    implementation(libs.coil.video)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.zxing.core)
    implementation(libs.zxing.cpp.android)
}
