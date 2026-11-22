// SPDX-FileCopyrightText: borz7zy
// SPDX-License-Identifier: MIT

//! Builds Samsung rlottie (`vendor/rlottie`, git submodule) as a static lib,
//! with the same configuration TelegramM used via rlottie's CMake:
//! LOTTIE_THREAD + LOTTIE_CACHE on, LOTTIE_MODULE off (stb_image linked in),
//! no exceptions/RTTI. Unlike TelegramM, SIMD stays on everywhere rlottie
//! has it: SSE2 draw helpers on x86, the pixman NEON asm on armv7. Sources
//! patched on the way into OUT_DIR leave the submodule clean.

use std::env;
use std::path::{Path, PathBuf};

const SOURCES: &[&str] = &[
    "binding/c/lottieanimation_capi.cpp",
    "lottie/lottieanimation.cpp",
    "lottie/lottieitem.cpp",
    "lottie/lottieitem_capi.cpp",
    "lottie/lottiekeypath.cpp",
    "lottie/lottieloader.cpp",
    "lottie/lottiemodel.cpp",
    "lottie/lottieparser.cpp",
    "lottie/lottieproxymodel.cpp",
    "lottie/zip/zip.cpp",
    "vector/freetype/v_ft_math.cpp",
    "vector/freetype/v_ft_raster.cpp",
    "vector/freetype/v_ft_stroker.cpp",
    "vector/stb/stb_image.cpp",
    "vector/varenaalloc.cpp",
    "vector/vbezier.cpp",
    "vector/vbitmap.cpp",
    "vector/vbrush.cpp",
    "vector/vdasher.cpp",
    "vector/vdebug.cpp",
    "vector/vdrawable.cpp",
    "vector/vdrawhelper.cpp",
    "vector/vdrawhelper_common.cpp",
    "vector/vdrawhelper_neon.cpp",
    "vector/vdrawhelper_sse2.cpp",
    "vector/velapsedtimer.cpp",
    "vector/vimageloader.cpp",
    "vector/vinterpolator.cpp",
    "vector/vmatrix.cpp",
    "vector/vpainter.cpp",
    "vector/vpath.cpp",
    "vector/vpathmesure.cpp",
    "vector/vraster.cpp",
    "vector/vrect.cpp",
    "vector/vrle.cpp",
];

const INCLUDES: &[&str] = &[
    "binding/c",
    "lottie",
    "lottie/zip",
    "vector",
    "vector/freetype",
    "vector/pixman",
    "vector/stb",
];

fn main() {
    let root = PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap()).join("vendor/rlottie");
    let src = root.join("src");
    let out = PathBuf::from(env::var("OUT_DIR").unwrap());
    let arm32 = env::var("CARGO_CFG_TARGET_ARCH").unwrap() == "arm";
    println!("cargo:rerun-if-changed=build.rs");
    println!("cargo:rerun-if-changed={}", src.display());
    assert!(
        src.join("lottie").is_dir(),
        "dec-tgs: vendor/rlottie is empty — run `git submodule update --init`"
    );

    // What cmake/config.h.in expands to with the options above.
    std::fs::write(
        out.join("config.h"),
        "#define LOTTIE_IMAGE_MODULE_PLUGIN \"\"\n#define LOTTIE_THREAD_SUPPORT\n#define LOTTIE_CACHE_SUPPORT\n",
    )
    .unwrap();

    let android = env::var("CARGO_CFG_TARGET_OS").unwrap() == "android";
    let mut b = cc::Build::new();
    b.cpp(true)
        .opt_level(3)
        .warnings(false)
        .include(root.join("inc"))
        .include(&out)
        .includes(INCLUDES.iter().map(|d| src.join(d)))
        .files(SOURCES.iter().map(|f| match *f {
            "binding/c/lottieanimation_capi.cpp" => patched_capi(&src, &out),
            "lottie/lottieitem.cpp" => patched_lottieitem(&src, &out),
            _ => src.join(f),
        }))
        .flag("-std=c++14")
        .flag("-fno-exceptions")
        .flag("-fno-rtti")
        .flag("-fno-unwind-tables")
        .flag("-fno-asynchronous-unwind-tables")
        .flag("-fvisibility=hidden")
        .flag("-ffunction-sections")
        .flag("-fdata-sections");
    if arm32 {
        // Every armeabi-v7a device has NEON (NDK default); rustc's target just doesn't assume it.
        // With __ARM_NEON__ set, vdrawhelper_neon.cpp routes fills/blends to pixman's asm.
        b.flag("-mfpu=neon").file(neon_asm(&src, &out));
    }
    if android {
        // cc would link libc++_shared.so, which then has to ship in the APK; link the NDK's static one
        // (-bundle: the NDK clang that links librustcore.so finds it in its sysroot).
        b.cpp_link_stdlib(None);
    }
    b.compile("rlottie");
    if android {
        println!("cargo:rustc-link-lib=static:-bundle=c++_static");
        println!("cargo:rustc-link-lib=static:-bundle=c++abi");
    }
}

/// The C binding enables caching by default, even for an empty key. Use the C++
/// cache policy so callers without a cache key each parse their own JSON.
fn patched_capi(src: &Path, out: &Path) -> PathBuf {
    let path = src.join("binding/c/lottieanimation_capi.cpp");
    let text = std::fs::read_to_string(path).unwrap();
    let call = "Animation::loadFromData(data, key, resourcePath)";
    assert!(text.contains(call), "dec-tgs: rlottie changed, re-check the C binding cache policy");
    let dst = out.join("lottieanimation_capi.cpp");
    std::fs::write(&dst, text.replace(call, "Animation::loadFromData(data, key, resourcePath, key[0] != '\\0')")).unwrap();
    dst
}

/// lottieitem.cpp with upstream 34465a9 ("shape-content budget") fixed. It
/// charges every *animated* path the worst case of 1024 points against a
/// 15000-point budget per composition, so a Telegram sticker with ~100
/// animated paths (~2000 real points) runs out after ~14 of them and the rest
/// of the shapes are silently not built — faces and outlines go missing.
/// Charge the real point count (largest keyframe) instead, and leave room for
/// heavy stickers: the most complex of a 27-sticker sample is ~14k real points.
fn patched_lottieitem(src: &Path, out: &Path) -> PathBuf {
    let path = src.join("lottie/lottieitem.cpp");
    let mut text = std::fs::read_to_string(&path).unwrap();
    for (old, new) in [
        (
            "static constexpr size_t kMaxShapeContentBudget = 15000;",
            "static constexpr size_t kMaxShapeContentBudget = 100000;",
        ),
        (
            "            return std::max<size_t>(1, data->mShape.value().mPoints.size());\n        }\n        return size_t(kMaxPolyPoints);",
            "            return std::max<size_t>(1, data->mShape.value().mPoints.size());\n        }\n        size_t pts = 1;\n        for (const auto &f : data->mShape.animation().frames_)\n            pts = std::max({pts, f.value_.start_.mPoints.size(), f.value_.end_.mPoints.size()});\n        return pts;",
        ),
    ] {
        assert!(text.contains(old), "dec-tgs: rlottie changed, re-check the lottieitem.cpp patch: {old}");
        text = text.replace(old, new);
    }
    // Same name, so `#include "lottieitem.h"` still resolves through the include paths.
    let dst = out.join("lottieitem.cpp");
    std::fs::write(&dst, text).unwrap();
    dst
}

/// pixman-arm-neon-asm.S made digestible for LLVM's integrated assembler
/// (the NDK has no GNU as), the way upstream pixman did it for clang:
/// - GNU-only `.func`/`.endfunc`/`.object_arch` (debug/ABI notes) dropped,
///   as is `local skip1` (`.altmacro`-only, and the label is never used);
/// - `.macro`/`.irp` arguments get an explicit `\`: LLVM doesn't substitute
///   bare `.altmacro` names, so `.if src_bpp_ == 32` fails;
/// - `.altmacro` concatenation `op&.&size`, `d&reg` becomes `\()`;
/// - the two pre-UAL mnemonics (`subges`, `ldrgeb`) become UAL.
fn neon_asm(src: &Path, out: &Path) -> PathBuf {
    let dir = src.join("vector/pixman");
    let dst = out.join("pixman");
    std::fs::create_dir_all(&dst).unwrap();
    for name in ["pixman-arm-neon-asm.S", "pixman-arm-neon-asm.h"] {
        let text = std::fs::read_to_string(dir.join(name)).unwrap();
        let fixed = llvm_asm(&text);
        assert!(!fixed.contains("\\\\"), "dec-tgs: double-escaped macro argument in {name}");
        std::fs::write(dst.join(name), fixed).unwrap();
    }
    dst.join("pixman-arm-neon-asm.S")
}

fn llvm_asm(text: &str) -> String {
    let mut out = String::new();
    // Arguments of each enclosing `.macro`/`.irp`/`.rept` (nested ones see the outer's too).
    let mut frames: Vec<Vec<String>> = Vec::new();
    // A `.macro` header may continue over `\`-ended lines.
    let mut header: Option<String> = None;
    let text = text.replace("subges", "subsge").replace("ldrgeb", "ldrbge");
    for line in text.lines() {
        let t = line.trim();
        let directive = t.split(|c: char| c.is_whitespace() || c == ',').next().unwrap_or("");
        if matches!(directive, ".func" | ".endfunc" | ".object_arch" | "local") {
            continue;
        }
        if header.is_some() || directive == ".macro" {
            let mut h = header.take().unwrap_or_default();
            h.push_str(t.trim_end_matches('\\'));
            h.push(' ');
            if t.ends_with('\\') {
                header = Some(h);
            } else {
                frames.push(args(&h));
            }
            out.push_str(line);
        } else if directive == ".irp" || directive == ".irpc" || directive == ".rept" {
            out.push_str(&escape_params(line, &frames));
            frames.push(if directive == ".rept" { Vec::new() } else { args(t).into_iter().take(1).collect() });
        } else if directive == ".endm" || directive == ".endr" {
            frames.pop();
            out.push_str(line);
        } else if frames.is_empty() {
            out.push_str(line);
        } else {
            out.push_str(&unconcat(&escape_params(line, &frames)));
        }
        out.push('\n');
    }
    out
}

/// Argument names after the directive and the macro name: `.macro name a, b = 1, c:vararg` →
/// `[a, b, c]`; `.irp x, 1, 2` → `[x, 1, 2]` (only the first is a name).
fn args(header: &str) -> Vec<String> {
    let mut words = header.trim().splitn(2, char::is_whitespace);
    let directive = words.next().unwrap_or("");
    let mut rest = words.next().unwrap_or("").trim_start();
    if directive == ".macro" {
        rest = rest.split_once(|c: char| c.is_whitespace() || c == ',').map_or("", |(_, r)| r);
    }
    rest.split(',')
        .filter_map(|p| p.split(|c: char| c == '=' || c == ':' || c.is_whitespace()).find(|w| !w.is_empty()))
        .map(str::to_string)
        .collect()
}

/// A lone `&` is `.altmacro` concatenation unless spaced on both sides (bitwise AND, as in
/// `(flags) & FLAG_X`); `&&` is logic.
fn unconcat(line: &str) -> String {
    let b = line.as_bytes();
    let at = |i: usize| b.get(i).copied().unwrap_or(b' ');
    let mut out = String::with_capacity(line.len() + 8);
    for (i, c) in line.char_indices() {
        let prev = if i == 0 { b' ' } else { b[i - 1] };
        let next = at(i + 1);
        if c == '&' && prev != b'&' && next != b'&' && !(prev == b' ' && next == b' ') {
            out.push_str("\\()");
        } else {
            out.push(c);
        }
    }
    out
}

/// Prefix each whole-word occurrence of a macro parameter with `\`.
fn escape_params(line: &str, frames: &[Vec<String>]) -> String {
    let ident = |c: char| c.is_ascii_alphanumeric() || c == '_';
    let mut out = String::with_capacity(line.len());
    let mut word = String::new();
    let mut prev = ' ';
    let flush = |word: &mut String, prev: char, out: &mut String| {
        if prev != '\\' && frames.iter().flatten().any(|p| p == word.as_str()) {
            out.push('\\');
        }
        out.push_str(word);
        word.clear();
    };
    let mut before = ' ';
    for c in line.chars() {
        if ident(c) {
            if word.is_empty() {
                before = prev;
            }
            word.push(c);
        } else {
            flush(&mut word, before, &mut out);
            out.push(c);
        }
        prev = c;
    }
    flush(&mut word, before, &mut out);
    out
}

