// SPDX-FileCopyrightText: borz7zy
// SPDX-License-Identifier: MIT

//! JNI surface for `com.github.borz7zy.rustcore.RustMedia`: sniff a file,
//! route it to the matching `dec-*` crate, hand back a Bitmap. Animated
//! stickers get a frame player: WebM frames live in the shared `frames` LRU
//! bucket, so the same sticker shown N times (or looping) is decoded once;
//! TGS frames are rendered by rlottie straight into the bitmap.

use std::sync::OnceLock;

use jni::objects::{JByteArray, JClass, JObject, JString};
use jni::sys::{jboolean, jint, jintArray, jlong, jobject, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use media::{Image, MediaKind, Result};

use crate::{bitmap, cache};

pub fn decode(data: &[u8], max_w: u32, max_h: u32) -> Result<Image> {
    match media::detect(data) {
        MediaKind::Jpeg => dec_jpeg::decode(data, max_w, max_h),
        MediaKind::Png => dec_png::decode(data, max_w, max_h),
        MediaKind::Webp => dec_webp::decode(data, max_w, max_h),
        MediaKind::Gif => dec_gif::decode(data, max_w, max_h),
        MediaKind::Webm => dec_webm::decode(data, max_w, max_h),
        MediaKind::Tgs => dec_tgs::decode(data, max_w, max_h),
        k => Err(media::err(format!("not ours: {k:?}"))),
    }
}

fn to_bitmap(env: &mut JNIEnv, data: &[u8], max_w: jint, max_h: jint) -> jobject {
    decode(data, max_w.max(0) as u32, max_h.max(0) as u32)
        .ok()
        .and_then(|img| bitmap::from_image(env, &img))
        .map_or(std::ptr::null_mut(), JObject::into_raw)
}

#[no_mangle]
pub extern "system" fn Java_com_github_borz7zy_rustcore_RustMedia_nativeDetect<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
    head: JByteArray<'l>,
) -> jint {
    env.convert_byte_array(&head).map_or(0, |b| media::detect(&b) as jint)
}

#[no_mangle]
pub extern "system" fn Java_com_github_borz7zy_rustcore_RustMedia_nativeDecodeFile<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    path: JString<'l>,
    max_w: jint,
    max_h: jint,
) -> jobject {
    let Ok(path) = env.get_string(&path).map(String::from) else { return std::ptr::null_mut() };
    let Ok(data) = std::fs::read(path) else { return std::ptr::null_mut() };
    to_bitmap(&mut env, &data, max_w, max_h)
}

#[no_mangle]
pub extern "system" fn Java_com_github_borz7zy_rustcore_RustMedia_nativeDecodeBytes<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    bytes: JByteArray<'l>,
    max_w: jint,
    max_h: jint,
) -> jobject {
    let Ok(data) = env.convert_byte_array(&bytes) else { return std::ptr::null_mut() };
    to_bitmap(&mut env, &data, max_w, max_h)
}

// ---- Animated sticker player (WebM, TGS) ------------------------------------

/// 32 MB of decoded WebM frames, RAM only.
// ponytail: raw RGBA; lz4 the frames if the bucket thrashes with many stickers on screen.
fn frames() -> jint {
    static H: OnceLock<jint> = OnceLock::new();
    *H.get_or_init(|| cache::open("frames".into(), 32 << 20, 0, false))
}

struct Webm {
    key: String,
    dec: dec_webm::WebmDecoder,
    /// Index of the frame the decoder will produce next.
    pos: usize,
}

impl Webm {
    fn frame_key(&self, i: usize) -> Vec<u8> {
        format!("{}\0{i}", self.key).into_bytes()
    }

    /// Frame `i`, from the LRU or decoded (caching every frame decoded on the way).
    fn frame(&mut self, i: usize, w: u32, h: u32) -> Option<Image> {
        let bucket = cache::bucket(frames())?;
        if let Some(rgba) = bucket.lock().ok()?.get(&self.frame_key(i)) {
            return Some(Image { width: w, height: h, rgba });
        }
        if i < self.pos {
            self.dec.rewind().ok()?;
            self.pos = 0;
        }
        loop {
            let (img, _) = self.dec.next_frame(w, h).ok()??;
            self.pos += 1;
            bucket.lock().ok()?.put(self.frame_key(self.pos - 1), img.rgba.clone());
            if self.pos - 1 == i {
                return Some(img);
            }
        }
    }
}

enum Source {
    Webm(Webm),
    // ponytail: TGS frames aren't cached — a 512² sticker is ~180 MB of frames; rlottie re-renders
    // each one (model is shared by key). Cache small sizes compressed if profiling says so.
    Tgs(dec_tgs::Animation),
}

struct Player {
    src: Source,
    w: u32,
    h: u32,
    frame_count: usize,
    duration_ms: u64,
}

fn player<'a>(h: jlong) -> Option<&'a mut Player> {
    // SAFETY: handles come from nativeStickerOpen and die in nativeStickerClose; the
    // Kotlin wrapper confines each one to a single thread.
    unsafe { (h as *mut Player).as_mut() }
}

fn open_webm(path: &str, data: &[u8], max_w: u32, max_h: u32) -> Option<Player> {
    let mut dec = dec_webm::WebmDecoder::new(data).ok()?;
    let (first, _) = dec.next_frame(max_w, max_h).ok()??;
    let (w, h) = (first.width, first.height);
    let webm = Webm { key: format!("{path}\0{w}x{h}"), dec, pos: 1 };
    if let Some(Ok(mut g)) = cache::bucket(frames()).map(|b| b.lock()) {
        g.put(webm.frame_key(0), first.rgba);
    }
    Some(Player { frame_count: webm.dec.frame_count(), duration_ms: webm.dec.duration_ms(), src: Source::Webm(webm), w, h })
}

fn open_tgs(path: &str, data: &[u8], max_w: u32, max_h: u32) -> Option<Player> {
    // rlottie keeps 10 parsed models by default; a sticker keyboard page shows 30+, so scrolling
    // back re-parsed every one (~2 ms each on the emulator). Keyed by path.
    // ponytail: fixed count, size it by bytes if big sets show up in memory profiles.
    static CACHE: std::sync::Once = std::sync::Once::new();
    CACHE.call_once(|| dec_tgs::set_model_cache_size(64));
    let a = dec_tgs::Animation::new(data, path).ok()?;
    let (w, h) = media::fit(a.width, a.height, max_w, max_h);
    Some(Player { frame_count: a.frame_count, duration_ms: a.duration_ms(), src: Source::Tgs(a), w, h })
}

/// Open a WebM or TGS sticker for playback at most `max_w×max_h`; 0 on failure.
#[no_mangle]
pub extern "system" fn Java_com_github_borz7zy_rustcore_RustMedia_nativeStickerOpen<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    path: JString<'l>,
    max_w: jint,
    max_h: jint,
) -> jlong {
    let Ok(path) = env.get_string(&path).map(String::from) else { return 0 };
    let Ok(data) = std::fs::read(&path) else { return 0 };
    let (max_w, max_h) = (max_w.max(0) as u32, max_h.max(0) as u32);
    let p = match media::detect(&data) {
        MediaKind::Webm => open_webm(&path, &data, max_w, max_h),
        MediaKind::Tgs => open_tgs(&path, &data, max_w, max_h),
        _ => None,
    };
    p.map_or(0, |p| Box::into_raw(Box::new(p)) as jlong)
}

/// `[width, height, frameCount, durationMs]`.
#[no_mangle]
pub extern "system" fn Java_com_github_borz7zy_rustcore_RustMedia_nativeStickerInfo<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
    h: jlong,
) -> jintArray {
    let Some(p) = player(h) else { return std::ptr::null_mut() };
    let info = [p.w as jint, p.h as jint, p.frame_count as jint, p.duration_ms as jint];
    let Ok(arr) = env.new_int_array(4) else { return std::ptr::null_mut() };
    if env.set_int_array_region(&arr, 0, &info).is_err() {
        return std::ptr::null_mut();
    }
    arr.into_raw()
}

/// Draw frame `index` into `bitmap` (ARGB_8888, `width×height` from info).
#[no_mangle]
pub extern "system" fn Java_com_github_borz7zy_rustcore_RustMedia_nativeStickerFrame<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    h: jlong,
    index: jint,
    bmp: JObject<'l>,
) -> jboolean {
    let Some(p) = player(h) else { return JNI_FALSE };
    let i = index.max(0) as usize;
    let ok = match &mut p.src {
        Source::Webm(webm) => webm.frame(i, p.w, p.h).is_some_and(|img| bitmap::fill(&mut env, &bmp, &img)),
        Source::Tgs(a) => {
            let (pw, ph) = (p.w as usize, p.h as usize);
            bitmap::with_pixels(&mut env, &bmp, |px, w, h, stride| {
                (w, h) == (pw, ph) && {
                    a.render(i, px, w, h, stride);
                    true
                }
            })
            .unwrap_or(false)
        }
    };
    if ok { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_github_borz7zy_rustcore_RustMedia_nativeStickerClose<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    h: jlong,
) {
    if h != 0 {
        drop(unsafe { Box::from_raw(h as *mut Player) });
    }
}
