// SPDX-FileCopyrightText: borz7zy
// SPDX-License-Identifier: MIT

//! Multi-frame RAW pipeline exposed to
//! `org.lineageos.aperture.pipeline.CameraPipeline` over JNI.

mod align;
mod color;
mod demosaic;
mod denoise;
mod img;
mod log;
mod merge;
mod moire;
mod night;
mod pipeline;
mod raw_prep;
mod refsel;
mod sharpen;
mod superres;
mod tonemap;

use jni::objects::{JByteBuffer, JClass, JFloatArray, JIntArray, JObjectArray};
use jni::sys::{jfloat, jfloatArray, jint, jintArray};
use jni::JNIEnv;
use std::panic::{catch_unwind, AssertUnwindSafe};

/// Parameter layout shared with CameraPipeline.kt (`PARAM_*`).
mod idx {
    pub const MODE: usize = 0;
    pub const WIDTH: usize = 1;
    pub const HEIGHT: usize = 2;
    pub const STRIDE: usize = 3;
    pub const CFA: usize = 4;
    pub const WHITE_LEVEL: usize = 5;
    pub const NOISE_S: usize = 6;
    pub const NOISE_O: usize = 10;
    pub const WB: usize = 14;
    pub const CCM: usize = 18;
    pub const R_HDR: usize = 27;
    pub const ROTATION: usize = 28;
    pub const COUNT: usize = 29;
    /// Per frame: 4 black levels (raster order) + TET.
    pub const FRAME_META: usize = 5;
}

fn floats(env: &JNIEnv, arr: &JFloatArray) -> jni::errors::Result<Vec<f32>> {
    let mut v = vec![0.0; env.get_array_length(arr)? as usize];
    env.get_float_array_region(arr, 0, &mut v)?;
    Ok(v)
}

/// SAFETY: the Java side passes direct ByteBuffers that stay alive for the call.
unsafe fn u16_buffer<'a>(env: &JNIEnv, buf: &JByteBuffer) -> jni::errors::Result<&'a mut [u16]> {
    let ptr = env.get_direct_buffer_address(buf)?;
    let len = env.get_direct_buffer_capacity(buf)?;
    Ok(std::slice::from_raw_parts_mut(ptr as *mut u16, len / 2))
}

fn throw(env: &mut JNIEnv, msg: &str) {
    let _ = env.throw_new("java/lang/RuntimeException", msg);
}

fn guarded<T>(env: &mut JNIEnv, default: T, f: impl FnOnce(&mut JNIEnv) -> Result<T, String>) -> T {
    match catch_unwind(AssertUnwindSafe(|| f(env))) {
        Ok(Ok(v)) => v,
        Ok(Err(e)) => {
            throw(env, &e);
            default
        }
        Err(_) => {
            throw(env, "camera_pipeline panicked");
            default
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_org_lineageos_aperture_pipeline_CameraPipeline_nativeProcess<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    frames: JObjectArray<'l>,
    frame_meta: JFloatArray<'l>,
    params: JFloatArray<'l>,
    lsc: JFloatArray<'l>,
    lsc_cols: jint,
    lsc_rows: jint,
    out_size: JIntArray<'l>,
) -> jintArray {
    guarded(&mut env, std::ptr::null_mut(), |env| {
        let e = |e: jni::errors::Error| e.to_string();
        let p = floats(env, &params).map_err(e)?;
        let meta = floats(env, &frame_meta).map_err(e)?;
        if p.len() < idx::COUNT {
            return Err("params too short".into());
        }
        let n = env.get_array_length(&frames).map_err(e)? as usize;
        if n == 0 || meta.len() < n * idx::FRAME_META {
            return Err("no frames / frame meta too short".into());
        }
        let arr4 = |o: usize| [p[o], p[o + 1], p[o + 2], p[o + 3]];
        let params = pipeline::Params {
            mode: match p[idx::MODE] as i32 {
                1 => pipeline::Mode::SuperRes,
                2 => pipeline::Mode::Night,
                _ => pipeline::Mode::Hdr,
            },
            width: p[idx::WIDTH] as usize,
            height: p[idx::HEIGHT] as usize,
            stride: p[idx::STRIDE] as usize,
            cfa: p[idx::CFA] as i32,
            white_level: p[idx::WHITE_LEVEL],
            noise_s: arr4(idx::NOISE_S),
            noise_o: arr4(idx::NOISE_O),
            wb: arr4(idx::WB),
            ccm: std::array::from_fn(|i| p[idx::CCM + i]),
            r_hdr: p[idx::R_HDR].max(1.0),
            rotation: p[idx::ROTATION] as i32,
        };
        let mut inputs = Vec::with_capacity(n);
        for i in 0..n {
            let obj = env.get_object_array_element(&frames, i as i32).map_err(e)?;
            let buf = JByteBuffer::from(obj);
            let data = unsafe { u16_buffer(env, &buf) }.map_err(e)?;
            if data.len() < params.stride * (params.height - 1) + params.width {
                return Err(format!("frame {i} buffer too small"));
            }
            let m = &meta[i * idx::FRAME_META..];
            inputs.push(pipeline::FrameIn { data, black_level: [m[0], m[1], m[2], m[3]], tet: m[4] });
        }
        let lsc_data = if lsc.is_null() { Vec::new() } else { floats(env, &lsc).map_err(e)? };
        let (cols, rows) = (lsc_cols.max(0) as usize, lsc_rows.max(0) as usize);
        let lsc = (cols >= 2 && rows >= 2 && lsc_data.len() >= 4 * cols * rows)
            .then(|| color::Lsc { map: &lsc_data, cols, rows });
        let (px, w, h) = pipeline::run(inputs, &params, lsc);
        env.set_int_array_region(&out_size, 0, &[w as i32, h as i32]).map_err(e)?;
        let out = env.new_int_array(px.len() as i32).map_err(e)?;
        let px: &[i32] = unsafe { std::slice::from_raw_parts(px.as_ptr() as *const i32, px.len()) };
        env.set_int_array_region(&out, 0, px).map_err(e)?;
        Ok(out.into_raw())
    })
}

#[no_mangle]
pub extern "system" fn Java_org_lineageos_aperture_pipeline_CameraPipeline_nativeHdrRatio<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    buffer: JByteBuffer<'l>,
    width: jint,
    height: jint,
    stride: jint,
    cfa: jint,
    black_level: JFloatArray<'l>,
    white_level: jfloat,
) -> jfloat {
    guarded(&mut env, 1.0, |env| {
        let e = |e: jni::errors::Error| e.to_string();
        let bl = floats(env, &black_level).map_err(e)?;
        let data = unsafe { u16_buffer(env, &buffer) }.map_err(e)?;
        let f = raw_prep::Frame::new(
            data,
            width as usize,
            height as usize,
            stride as usize,
            cfa,
            [bl[0], bl[1], bl[2], bl[3]],
            white_level,
            1.0,
        );
        Ok(raw_prep::hdr_ratio(&f))
    })
}

#[no_mangle]
pub extern "system" fn Java_org_lineageos_aperture_pipeline_CameraPipeline_nativeNightPlan<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    tet: jfloat,
    omega: jfloat,
    focal_px: jfloat,
    iso_min: jfloat,
    iso_max: jfloat,
    exp_max_ms: jfloat,
) -> jfloatArray {
    guarded(&mut env, std::ptr::null_mut(), |env| {
        let pl = night::plan(tet, omega, focal_px, iso_min, iso_max, exp_max_ms);
        let out = env.new_float_array(4).map_err(|e| e.to_string())?;
        env.set_float_array_region(&out, 0, &[pl.exposure_ms, pl.iso, pl.frames as f32, pl.tripod as u8 as f32])
            .map_err(|e| e.to_string())?;
        Ok(out.into_raw())
    })
}

/// Frame buffers live in native memory: 10-16 RAW16 frames don't fit the 256 MB Java heap
/// that direct ByteBuffers are charged to on ART.
#[no_mangle]
pub extern "system" fn Java_org_lineageos_aperture_pipeline_CameraPipeline_nativeAlloc<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    size: jint,
) -> jni::sys::jobject {
    guarded(&mut env, std::ptr::null_mut(), |env| {
        let layout = std::alloc::Layout::from_size_align(size.max(2) as usize, 2)
            .map_err(|e| e.to_string())?;
        // SAFETY: non-zero size; freed by nativeFree with the same layout.
        let ptr = unsafe { std::alloc::alloc(layout) };
        if ptr.is_null() {
            return Err(format!("Out of native memory ({size} bytes)"));
        }
        // SAFETY: ptr is valid for `size` bytes until nativeFree.
        unsafe { env.new_direct_byte_buffer(ptr, layout.size()) }
            .map(|b| b.into_raw())
            .map_err(|e| e.to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_org_lineageos_aperture_pipeline_CameraPipeline_nativeFree<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    buf: JByteBuffer<'l>,
) {
    guarded(&mut env, (), |env| {
        let ptr = env.get_direct_buffer_address(&buf).map_err(|e| e.to_string())?;
        let len = env.get_direct_buffer_capacity(&buf).map_err(|e| e.to_string())?;
        // SAFETY: buffer came from nativeAlloc with this size and alignment.
        unsafe { std::alloc::dealloc(ptr, std::alloc::Layout::from_size_align_unchecked(len, 2)) };
        Ok(())
    })
}
