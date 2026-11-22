// SPDX-FileCopyrightText: borz7zy
// SPDX-License-Identifier: MIT

//! `librustcore.so`: the one native library behind the `:RustCore` module.
//! All JNI exports live here; the work is done by the workspace crates.

// Aperture's burst pipeline: link it in so its Java_org_lineageos_aperture_* exports ship here.
extern crate camera_pipeline;

mod bitmap;
mod cache;
mod call;
mod decode;
mod haptics;
