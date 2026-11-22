/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

import org.gradle.api.tasks.Exec

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.github.borz7zy.rustcore"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
    }

    ndkVersion = "28.2.13676358"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // cargo-ndk writes build/rustJniLibs/<abi>/librustcore.so; AGP packs it like any jniLibs.
    sourceSets {
        getByName("main") {
            jniLibs.directories.add("build/rustJniLibs")
        }
    }
}

// -------- Rust build (cargo workspace in native/) ---------------------------

val rustDir = file("native")
val rustAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
val rustJniLibsDir = layout.buildDirectory.dir("rustJniLibs")

// Plain strings only: the configuration cache forbids capturing `android` in the task.
val sdkDir: String = androidComponents.sdkComponents.sdkDirectory.get().asFile.absolutePath
val ndkHomePath: String = "$sdkDir/ndk/${android.ndkVersion}"
val outPath: String = rustJniLibsDir.get().asFile.absolutePath
// rustup's cargo (has cargo-ndk); a distro /usr/bin/cargo may shadow it in PATH.
val cargoBin: String = File(System.getProperty("user.home"), ".cargo/bin/cargo")
    .takeIf { it.canExecute() }?.absolutePath ?: "cargo"

val cargoBuildNative = tasks.register<Exec>("cargoBuildNative") {
    group = "build"
    description = "Builds librustcore.so for all configured Android ABIs."

    workingDir = rustDir
    inputs.files(fileTree(rustDir) {
        include("Cargo.lock", "Cargo.toml", "*/Cargo.toml", "*/build.rs", "*/src/**")
    })
    outputs.dir(rustJniLibsDir)

    environment("ANDROID_NDK_HOME", ndkHomePath)
    commandLine(buildList {
        add(cargoBin); add("ndk")
        add("-P"); add("29")
        rustAbis.forEach { add("-t"); add(it) }
        add("-o"); add(outPath)
        add("build"); add("--locked"); add("--release"); add("-p"); add("rustcore")
    })
}

tasks.named("preBuild") { dependsOn(cargoBuildNative) }
afterEvaluate {
    tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
        .configureEach { dependsOn(cargoBuildNative) }
}

tasks.named("clean") {
    // Keep native/target: cargo's incremental cache (and libvpx builds) are slow to redo.
    doLast { delete(rustJniLibsDir) }
}
