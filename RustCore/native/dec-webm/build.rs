// SPDX-FileCopyrightText: borz7zy
// SPDX-License-Identifier: MIT

//! Builds a decoder-only static libvpx (VP9) from `vendor/libvpx` with its own
//! `configure`, using whatever C compiler `cc` resolves for the target (the
//! NDK clang under cargo-ndk). x86 asm needs nasm; without it we fall back to C.

use std::env;
use std::path::PathBuf;
use std::process::Command;

fn main() {
    println!("cargo:rerun-if-changed=build.rs");
    let src = PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap()).join("vendor/libvpx");
    let out = PathBuf::from(env::var("OUT_DIR").unwrap()).join("libvpx");
    let lib = out.join("libvpx.a");

    if !lib.exists() {
        std::fs::create_dir_all(&out).unwrap();
        let target = env::var("TARGET").unwrap();
        let vpx_target = match target.as_str() {
            "aarch64-linux-android" => "arm64-android-gcc",
            "armv7-linux-androideabi" => "armv7-android-gcc",
            "x86_64-linux-android" => "x86_64-android-gcc",
            "i686-linux-android" => "x86-android-gcc",
            "x86_64-unknown-linux-gnu" => "x86_64-linux-gcc",
            "aarch64-unknown-linux-gnu" => "arm64-linux-gcc",
            t => panic!("dec-webm: no libvpx target mapping for {t}"),
        };
        let cc = cc::Build::new().opt_level(3).get_compiler();
        let cflags: Vec<String> = cc.args().iter().map(|a| a.to_string_lossy().into_owned()).collect();
        let target_flags = cflags.iter().filter(|f| f.starts_with("--target")).cloned().collect::<Vec<_>>().join(" ");
        let ar = cc::Build::new().get_archiver();
        let x86 = vpx_target.starts_with("x86");
        let have_nasm = Command::new("nasm").arg("-v").output().is_ok();

        let mut conf = Command::new(src.join("configure"));
        conf.current_dir(&out)
            .env("CC", cc.path())
            .env("CXX", cc.path()) // never used: no C++ parts are enabled
            .env("LD", cc.path())
            .env("AS", if x86 { "nasm".into() } else { cc.path().to_path_buf() })
            .env("AR", ar.get_program())
            .env("CFLAGS", cflags.join(" "))
            // The linker (configure test-links) and clang-as-assembler need the target too.
            .env("LDFLAGS", &target_flags)
            .env("ASFLAGS", if x86 { "" } else { &target_flags })
            .arg(format!("--target={vpx_target}"))
            .args([
                "--disable-vp8",
                "--disable-vp9-encoder",
                "--disable-examples",
                "--disable-tools",
                "--disable-docs",
                "--disable-unit-tests",
                "--disable-webm-io",
                "--disable-libyuv",
                "--disable-install-bins",
                "--disable-install-docs",
                "--disable-debug",
                "--enable-pic",
                "--enable-multithread",
                "--size-limit=4096x4096",
            ]);
        if x86 && !have_nasm {
            conf.arg("--disable-x86-asm");
        }
        run(&mut conf);
        let jobs = env::var("NUM_JOBS").unwrap_or_else(|_| "4".into());
        run(Command::new("make").current_dir(&out).arg(format!("-j{jobs}")).arg("libvpx.a"));
    }

    println!("cargo:rustc-link-search=native={}", out.display());
    println!("cargo:rustc-link-lib=static=vpx");
}

fn run(cmd: &mut Command) {
    let st = cmd.status().unwrap_or_else(|e| panic!("dec-webm: failed to spawn {cmd:?}: {e}"));
    assert!(st.success(), "dec-webm: {cmd:?} failed with {st}");
}
