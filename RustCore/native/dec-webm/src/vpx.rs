// SPDX-FileCopyrightText: borz7zy
// SPDX-License-Identifier: MIT

//! The handful of libvpx decoder calls we need, hand-declared against
//! libvpx 1.17 headers (vendor/libvpx/vpx/vpx_{codec,decoder,image}.h).

use std::ffi::{c_char, c_int, c_long, c_uint, c_void};
use std::marker::PhantomData;

use media::{err, Image, Result};

// VPX_DECODER_ABI_VERSION = 3 + VPX_CODEC_ABI_VERSION(4 + VPX_IMAGE_ABI_VERSION(5))
const VPX_DECODER_ABI_VERSION: c_int = 12;
const VPX_CR_FULL_RANGE: c_int = 1;
const VPX_IMG_FMT_HIGHBITDEPTH: c_int = 0x800;

#[repr(C)]
struct CodecCtx {
    name: *const c_char,
    iface: *const c_void,
    err: c_int,
    err_detail: *const c_char,
    init_flags: c_long,
    config: *const c_void,
    priv_: *mut c_void,
}

#[repr(C)]
struct DecCfg {
    threads: c_uint,
    w: c_uint,
    h: c_uint,
}

#[repr(C)]
struct VpxImage {
    fmt: c_int,
    cs: c_int,
    range: c_int,
    w: c_uint,
    h: c_uint,
    bit_depth: c_uint,
    d_w: c_uint,
    d_h: c_uint,
    r_w: c_uint,
    r_h: c_uint,
    x_chroma_shift: c_uint,
    y_chroma_shift: c_uint,
    planes: [*mut u8; 4],
    stride: [c_int; 4],
    bps: c_int,
    user_priv: *mut c_void,
    img_data: *mut u8,
    img_data_owner: c_int,
    self_allocd: c_int,
    fb_priv: *mut c_void,
}

extern "C" {
    fn vpx_codec_vp9_dx() -> *const c_void;
    fn vpx_codec_dec_init_ver(ctx: *mut CodecCtx, iface: *const c_void, cfg: *const DecCfg, flags: c_long, ver: c_int) -> c_int;
    fn vpx_codec_decode(ctx: *mut CodecCtx, data: *const u8, size: c_uint, user_priv: *mut c_void, deadline: c_long) -> c_int;
    fn vpx_codec_get_frame(ctx: *mut CodecCtx, iter: *mut *const c_void) -> *const VpxImage;
    fn vpx_codec_destroy(ctx: *mut CodecCtx) -> c_int;
}

pub struct Decoder(Box<CodecCtx>);

// The context is only touched through &mut self.
unsafe impl Send for Decoder {}

impl Decoder {
    pub fn new() -> Result<Self> {
        // Stickers are ≤512², and many may play at once: one thread each.
        let cfg = DecCfg { threads: 1, w: 0, h: 0 };
        let mut ctx: Box<CodecCtx> = Box::new(unsafe { std::mem::zeroed() });
        let rc = unsafe { vpx_codec_dec_init_ver(&mut *ctx, vpx_codec_vp9_dx(), &cfg, 0, VPX_DECODER_ABI_VERSION) };
        if rc != 0 {
            return Err(err(format!("vpx: init failed ({rc})")));
        }
        Ok(Self(ctx))
    }

    /// Decode one packet; the frame borrows the decoder until the next call.
    pub fn decode(&mut self, data: &[u8]) -> Result<Frame<'_>> {
        unsafe {
            let rc = vpx_codec_decode(&mut *self.0, data.as_ptr(), data.len() as c_uint, std::ptr::null_mut(), 0);
            if rc != 0 {
                return Err(err(format!("vpx: decode failed ({rc})")));
            }
            let mut iter = std::ptr::null();
            let img = vpx_codec_get_frame(&mut *self.0, &mut iter);
            if img.is_null() {
                return Err(err("vpx: no frame"));
            }
            if (*img).fmt & VPX_IMG_FMT_HIGHBITDEPTH != 0 {
                return Err(err("vpx: high bit depth unsupported"));
            }
            Ok(Frame(&*img, PhantomData))
        }
    }
}

impl Drop for Decoder {
    fn drop(&mut self) {
        unsafe { vpx_codec_destroy(&mut *self.0) };
    }
}

pub struct Frame<'a>(&'a VpxImage, PhantomData<&'a mut Decoder>);

impl Frame<'_> {
    fn plane(&self, i: usize, x: u32, y: u32) -> u8 {
        let im = self.0;
        unsafe { *im.planes[i].offset(y as isize * im.stride[i] as isize + x as isize) }
    }

    /// YUV (+ alpha decoder's luma) → premultiplied RGBA.
    pub fn to_rgba(&self, alpha: Option<&Frame>) -> Image {
        let im = self.0;
        let (w, h) = (im.d_w, im.d_h);
        let (sx, sy) = (im.x_chroma_shift, im.y_chroma_shift);
        let full = im.range == VPX_CR_FULL_RANGE;
        let mut rgba = Vec::with_capacity((w * h * 4) as usize);
        // ponytail: BT.601 only (what VP9 stickers use); add BT.709 by `cs` if real files need it.
        for y in 0..h {
            for x in 0..w {
                let yy = self.plane(0, x, y) as i32;
                let u = self.plane(1, x >> sx, y >> sy) as i32 - 128;
                let v = self.plane(2, x >> sx, y >> sy) as i32 - 128;
                let (r, g, b) = if full {
                    let c = yy << 8;
                    (c + 359 * v, c - 88 * u - 183 * v, c + 454 * u)
                } else {
                    let c = 298 * (yy - 16);
                    (c + 409 * v, c - 100 * u - 208 * v, c + 516 * u)
                };
                let a = alpha.map_or(255, |f| f.plane(0, x.min(f.0.d_w - 1), y.min(f.0.d_h - 1)) as i32);
                let px = |c: i32| (((c + 128) >> 8).clamp(0, 255) * a + 127) / 255;
                rgba.extend_from_slice(&[px(r) as u8, px(g) as u8, px(b) as u8, a as u8]);
            }
        }
        Image { width: w, height: h, rgba }
    }
}
