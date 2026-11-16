/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.drinkless.tdlib"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 29

        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // TdApi only needs androidx.annotation
    implementation(libs.androidx.core.ktx)
}
