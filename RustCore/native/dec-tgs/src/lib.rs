// SPDX-FileCopyrightText: borz7zy
// SPDX-License-Identifier: MIT

//! TGS (gzip'd Lottie JSON) stickers and emoji through Samsung rlottie's C API.
//!
//! rlottie renders premultiplied ARGB32 words — bytes `B G R A` in memory —
//! while an Android `ARGB_8888` bitmap wants `R G B A`, so every render ends
//! with an R/B swap.

use std::ffi::{c_char, CString};

use media::{err, Image, Result};

mod ffi {
    use std::ffi::c_char;

    #[repr(C)]
    pub struct LottieAnimation {
        _opaque: [u8; 0],
    }

    extern "C" {
        pub fn lottie_animation_from_data(data: *const c_char, key: *const c_char, resource_path: *const c_char) -> *mut LottieAnimation;
        pub fn lottie_animation_destroy(a: *mut LottieAnimation);
        pub fn lottie_animation_get_size(a: *const LottieAnimation, w: *mut usize, h: *mut usize);
        pub fn lottie_animation_get_duration(a: *const LottieAnimation) -> f64;
        pub fn lottie_animation_get_totalframe(a: *const LottieAnimation) -> usize;
        pub fn lottie_animation_get_framerate(a: *const LottieAnimation) -> f64;
        pub fn lottie_animation_render(a: *mut LottieAnimation, frame: usize, buf: *mut u32, w: usize, h: usize, bytes_per_line: usize);
        pub fn lottie_configure_model_cache_size(size: usize);
    }
}

/// Telegram caps TGS at 64 KB gzip'd; real stickers inflate to well under 1 MB.
/// Anything past this is a zip bomb, not a sticker.
const MAX_JSON: usize = 16 << 20;

/// A loaded Lottie animation. Not `Sync`: rlottie's render isn't re-entrant
/// for one animation. Different animations render in parallel fine.
pub struct Animation {
    ptr: *mut ffi::LottieAnimation,
    pub width: u32,
    pub height: u32,
    pub frame_count: usize,
    pub fps: f64,
}

// SAFETY: the handle owns its model; rlottie keeps no thread affinity.
unsafe impl Send for Animation {}

impl Animation {
    /// `data` is a TGS (gzip) or plain Lottie JSON. Animations loaded with
    /// the same non-empty `key` share one parsed model (rlottie's cache), so
    /// pass something unique to the content, like the file path.
    pub fn new(data: &[u8], key: &str) -> Result<Self> {
        let json = if data.starts_with(&[0x1F, 0x8B]) {
            let opts = zune_inflate::DeflateOptions::default().set_limit(MAX_JSON).set_size_hint(data.len() * 8);
            zune_inflate::DeflateDecoder::new_with_options(data, opts).decode_gzip().map_err(|e| err(format!("tgs: {e:?}")))?
        } else {
            data.to_vec()
        };
        let json = CString::new(json).map_err(|_| err("tgs: NUL in json"))?;
        let key = CString::new(key).map_err(|_| err("tgs: NUL in key"))?;
        let ptr = unsafe { ffi::lottie_animation_from_data(json.as_ptr(), key.as_ptr(), c"".as_ptr() as *const c_char) };
        if ptr.is_null() {
            return Err(err("tgs: rlottie could not load it"));
        }
        let (mut w, mut h) = (0usize, 0usize);
        let a = unsafe {
            ffi::lottie_animation_get_size(ptr, &mut w, &mut h);
            Self { ptr, width: w as u32, height: h as u32, frame_count: ffi::lottie_animation_get_totalframe(ptr), fps: ffi::lottie_animation_get_framerate(ptr) }
        };
        if a.width == 0 || a.height == 0 || a.frame_count == 0 {
            return Err(err("tgs: empty animation"));
        }
        Ok(a)
    }

    pub fn duration_ms(&self) -> u64 {
        (unsafe { ffi::lottie_animation_get_duration(self.ptr) } * 1000.0) as u64
    }

    /// Render frame `index` (wrapped into range) scaled into `w×h` (aspect
    /// kept, centred) as premultiplied RGBA bytes. `px` is `h` rows of
    /// `stride` pixels.
    pub fn render(&mut self, index: usize, px: &mut [u32], w: usize, h: usize, stride: usize) {
        assert!(w > 0 && h > 0 && w <= stride && px.len() >= stride * (h - 1) + w, "tgs: buffer too small");
        unsafe { ffi::lottie_animation_render(self.ptr, index % self.frame_count, px.as_mut_ptr(), w, h, stride * 4) };
        for row in px.chunks_mut(stride).take(h) {
            for p in &mut row[..w] {
                // Word 0xAARRGGBB → bytes R G B A.
                *p = (*p & 0xFF00_FF00) | ((*p >> 16) & 0xFF) | ((*p & 0xFF) << 16);
            }
        }
    }

    /// Frame `index` fitted into `max_w×max_h` (0 = no limit, never upscaled).
    pub fn image(&mut self, index: usize, max_w: u32, max_h: u32) -> Image {
        let (w, h) = media::fit(self.width, self.height, max_w, max_h);
        let mut px = vec![0u32; (w * h) as usize];
        self.render(index, &mut px, w as usize, h as usize, w as usize);
        Image { width: w, height: h, rgba: px.into_iter().flat_map(u32::to_ne_bytes).collect() }
    }
}

impl Drop for Animation {
    fn drop(&mut self) {
        unsafe { ffi::lottie_animation_destroy(self.ptr) }
    }
}

/// How many parsed models rlottie keeps for reuse by key (its default is 10).
pub fn set_model_cache_size(n: usize) {
    unsafe { ffi::lottie_configure_model_cache_size(n) }
}

/// First frame as a still image.
pub fn decode(data: &[u8], max_w: u32, max_h: u32) -> Result<Image> {
    Ok(Animation::new(data, "")?.image(0, max_w, max_h))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> Vec<u8> {
        std::fs::read(concat!(env!("CARGO_MANIFEST_DIR"), "/../testdata/sticker.tgs")).unwrap()
    }

    #[test]
    fn renders_animated_rgba() {
        let mut a = Animation::new(&sample(), "test").unwrap();
        assert!(a.frame_count > 1 && a.duration_ms() > 0);
        let f0 = a.image(0, 128, 128);
        let mid = a.image(a.frame_count / 2, 128, 128);
        assert_eq!((f0.width, f0.height), (128, 128));
        let opaque = f0.rgba.chunks_exact(4).filter(|p| p[3] != 0).count();
        assert!(opaque > 128 * 128 / 10, "sticker must draw something, got {opaque} px");
        assert!(f0.rgba.chunks_exact(4).all(|p| p[0] <= p[3] && p[1] <= p[3] && p[2] <= p[3]), "premultiplied");
        assert_ne!(f0.rgba, mid.rgba, "frames must differ");
    }

    /// rlottie 34465a9 charged each animated path 1024 points of a 15000 budget,
    /// so only the first ~14 got built (see `patched_lottieitem` in build.rs).
    #[test]
    fn draws_every_animated_path() {
        let square = |x: i32, y: i32| {
            let v = format!("[[{x},{y}],[{},{y}],[{},{}],[{x},{}]]", x + 10, x + 10, y + 10, y + 10);
            let z = "[[0,0],[0,0],[0,0],[0,0]]";
            let shape = format!(r#"[{{"i":{z},"o":{z},"v":{v},"c":true}}]"#);
            format!(
                r#"{{"ty":"gr","it":[
                {{"ty":"sh","ks":{{"a":1,"k":[{{"t":0,"s":{shape},"e":{shape},"i":{{"x":[0.5],"y":[0.5]}},"o":{{"x":[0.5],"y":[0.5]}}}},{{"t":10}}]}}}},
                {{"ty":"fl","c":{{"a":0,"k":[1,0,0,1]}},"o":{{"a":0,"k":100}}}},
                {{"ty":"tr","p":{{"a":0,"k":[0,0]}},"a":{{"a":0,"k":[0,0]}},"s":{{"a":0,"k":[100,100]}},"r":{{"a":0,"k":0}},"o":{{"a":0,"k":100}}}}]}}"#
            )
        };
        let cells: Vec<(i32, i32)> = (0..30).map(|i| (10 + (i % 6) * 50, 10 + (i / 6) * 50)).collect();
        let groups: Vec<String> = cells.iter().map(|&(x, y)| square(x, y)).collect();
        let t = r#"{"o":{"a":0,"k":100},"r":{"a":0,"k":0},"p":{"a":0,"k":[0,0,0]},"a":{"a":0,"k":[0,0,0]},"s":{"a":0,"k":[100,100,100]}}"#;
        let json = format!(
            r#"{{"v":"5.8.1","w":300,"h":300,"fr":60,"ip":0,"op":10,"layers":[{{"ty":4,"ind":1,"ip":0,"op":10,"st":0,"ks":{t},"shapes":[{}]}}]}}"#,
            groups.join(",")
        );
        let img = Animation::new(json.as_bytes(), "").unwrap().image(0, 0, 0);
        for (x, y) in cells {
            let i = (((y + 5) * 300 + x + 5) * 4) as usize;
            assert_eq!(img.rgba[i + 3], 255, "square at {x},{y} not drawn");
        }
    }

    #[test]
    fn rejects_garbage() {
        assert!(Animation::new(b"\x1f\x8bnot gzip", "").is_err());
        assert!(Animation::new(b"{}", "").is_err());
    }
}
