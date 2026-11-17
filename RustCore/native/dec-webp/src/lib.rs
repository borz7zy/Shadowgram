// SPDX-FileCopyrightText: borz7zy
// SPDX-License-Identifier: MIT

//! WebP (static stickers / custom emoji) via libwebp — NEON/SSE2 C code, the
//! fastest WebP decoder around. libwebp scales during decode and emits
//! premultiplied RGBA (`MODE_rgbA`) directly, so no extra passes.

use libwebp_sys::*;
use media::{err, Image, Result};

pub fn decode(data: &[u8], max_w: u32, max_h: u32) -> Result<Image> {
    unsafe {
        let mut cfg: WebPDecoderConfig = std::mem::zeroed();
        if !WebPInitDecoderConfig(&mut cfg) {
            return Err(err("webp: version mismatch"));
        }
        if WebPGetFeatures(data.as_ptr(), data.len(), &mut cfg.input) != VP8StatusCode::VP8_STATUS_OK {
            return Err(err("webp: bad header"));
        }
        let (w, h) = (cfg.input.width as u32, cfg.input.height as u32);
        let (tw, th) = media::fit(w, h, max_w, max_h);
        if (tw, th) != (w, h) {
            cfg.options.use_scaling = 1;
            cfg.options.scaled_width = tw as i32;
            cfg.options.scaled_height = th as i32;
        }
        let mut rgba = media::rgba_buffer(tw, th)?;
        cfg.output.colorspace = WEBP_CSP_MODE::MODE_rgbA;
        cfg.output.is_external_memory = 1;
        cfg.output.u.RGBA = WebPRGBABuffer { rgba: rgba.as_mut_ptr(), stride: (tw * 4) as i32, size: rgba.len() };
        let st = WebPDecode(data.as_ptr(), data.len(), &mut cfg);
        WebPFreeDecBuffer(&mut cfg.output);
        if st != VP8StatusCode::VP8_STATUS_OK {
            return Err(err(format!("webp: decode failed {st:?}")));
        }
        Ok(Image { width: tw, height: th, rgba })
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn decodes_and_scales() {
        let webp = include_bytes!("../../testdata/sticker.webp");
        let img = super::decode(webp, 0, 0).unwrap();
        assert!(img.rgba.chunks_exact(4).any(|p| p[3] == 0), "sticker has transparent pixels");
        let small = super::decode(webp, 100, 100).unwrap();
        assert!(small.width <= 100 && small.height <= 100);
    }
}
