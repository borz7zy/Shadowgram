import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
}

// Telegram API credentials (https://my.telegram.org), kept out of version control
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

fun telegramApiProperty(name: String) = localProperties.getProperty(name)?.takeIf { it.isNotBlank() }
    ?: error("$name is missing in local.properties")

android {
    namespace = "com.github.borz7zy.shadowgram"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.github.borz7zy.shadowgram"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("int", "TELEGRAM_API_ID", telegramApiProperty("api_id"))
        buildConfigField("String", "TELEGRAM_API_HASH", "\"${telegramApiProperty("api_hash")}\"")
        buildConfigField("int", "TELEGRAM_TEST_DC", telegramApiProperty("test_dc"))
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":tdlib"))
    implementation(project(":RustCore"))
    implementation(project(":aperture"))

    implementation(libs.androidx.activity)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.media3.common.ktx)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.viewpager2)
    implementation(libs.coil)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.material)
    implementation(libs.zxing.core)
    implementation(libs.nier.visualizer) {
        exclude(group = "com.android.support")
    }

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
