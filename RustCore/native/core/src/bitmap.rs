// SPDX-FileCopyrightText: borz7zy
// SPDX-License-Identifier: MIT

//! Pixels → `android.graphics.Bitmap` through jnigraphics (one memcpy into
//! the bitmap's own storage, nothing on the Java heap).

use jni::objects::{JObject, JValue};
use jni::JNIEnv;
use media::Image;

#[cfg(target_os = "android")]
mod ffi {
    use std::ffi::{c_int, c_void};

    #[repr(C)]
    pub struct AndroidBitmapInfo {
        pub width: u32,
        pub height: u32,
        pub stride: u32,
        pub format: i32,
        pub flags: u32,
    }

    pub const ANDROID_BITMAP_FORMAT_RGBA_8888: i32 = 1;

    #[link(name = "jnigraphics")]
    extern "C" {
        pub fn AndroidBitmap_getInfo(env: *mut jni::sys::JNIEnv, bmp: jni::sys::jobject, info: *mut AndroidBitmapInfo) -> c_int;
        pub fn AndroidBitmap_lockPixels(env: *mut jni::sys::JNIEnv, bmp: jni::sys::jobject, addr: *mut *mut c_void) -> c_int;
        pub fn AndroidBitmap_unlockPixels(env: *mut jni::sys::JNIEnv, bmp: jni::sys::jobject) -> c_int;
    }
}

/// New mutable ARGB_8888 bitmap of `w×h`.
pub fn create<'l>(env: &mut JNIEnv<'l>, w: u32, h: u32) -> Option<JObject<'l>> {
    let cfg = env
        .get_static_field("android/graphics/Bitmap$Config", "ARGB_8888", "Landroid/graphics/Bitmap$Config;")
        .ok()?
        .l()
        .ok()?;
    env.call_static_method(
        "android/graphics/Bitmap",
        "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;",
        &[JValue::Int(w as i32), JValue::Int(h as i32), JValue::Object(&cfg)],
    )
    .ok()?
    .l()
    .ok()
}

/// Run `f(pixels, width, height, stride_in_pixels)` on the locked pixels of
/// an RGBA_8888 `bmp`; `None` if it isn't one or can't be locked.
#[cfg(target_os = "android")]
pub fn with_pixels<R>(env: &mut JNIEnv, bmp: &JObject, f: impl FnOnce(&mut [u32], usize, usize, usize) -> R) -> Option<R> {
    unsafe {
        let mut info: ffi::AndroidBitmapInfo = std::mem::zeroed();
        if ffi::AndroidBitmap_getInfo(env.get_raw(), bmp.as_raw(), &mut info) != 0
            || info.format != ffi::ANDROID_BITMAP_FORMAT_RGBA_8888
            || info.width == 0
            || info.height == 0
        {
            return None;
        }
        let mut px = std::ptr::null_mut();
        if ffi::AndroidBitmap_lockPixels(env.get_raw(), bmp.as_raw(), &mut px) != 0 || px.is_null() {
            return None;
        }
        // Bitmap rows are 4-byte aligned and `stride` is a multiple of 4 for RGBA_8888.
        let stride = info.stride as usize / 4;
        let (w, h) = (info.width as usize, info.height as usize);
        let pixels = std::slice::from_raw_parts_mut(px as *mut u32, stride * (h - 1) + w);
        let r = f(pixels, w, h, stride);
        ffi::AndroidBitmap_unlockPixels(env.get_raw(), bmp.as_raw());
        Some(r)
    }
}

// Host builds (cargo check / clippy on the dev machine) have no jnigraphics.
#[cfg(not(target_os = "android"))]
pub fn with_pixels<R>(_: &mut JNIEnv, _: &JObject, _: impl FnOnce(&mut [u32], usize, usize, usize) -> R) -> Option<R> {
    None
}

/// Copy `img` into `bmp`, which must be RGBA_8888 with the same size.
pub fn fill(env: &mut JNIEnv, bmp: &JObject, img: &Image) -> bool {
    with_pixels(env, bmp, |px, w, h, stride| {
        if (w, h) != (img.width as usize, img.height as usize) {
            return false;
        }
        for (y, row) in img.rgba.chunks_exact(w * 4).enumerate() {
            let dst = &mut px[y * stride..y * stride + w];
            // SAFETY: `dst` holds exactly `w` pixels = `row.len()` bytes.
            unsafe { std::ptr::copy_nonoverlapping(row.as_ptr(), dst.as_mut_ptr().cast::<u8>(), row.len()) };
        }
        true
    })
    .unwrap_or(false)
}

/// Fresh bitmap holding `img`, or null.
pub fn from_image<'l>(env: &mut JNIEnv<'l>, img: &Image) -> Option<JObject<'l>> {
    let bmp = create(env, img.width, img.height)?;
    fill(env, &bmp, img).then_some(bmp)
}
