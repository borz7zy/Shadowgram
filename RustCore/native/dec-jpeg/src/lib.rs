// SPDX-FileCopyrightText: borz7zy
// SPDX-License-Identifier: MIT

//! JPEG via `libjpeg-turbo-rs` (pure Rust, NEON/SSE/AVX2). Photos, previews,
//! avatars and TDLib minithumbnails (TDLib already expands the stripped
//! format to a full JPEG).
//!
//! Downscaling happens in the DCT domain first — decoding at 1/8 skips most of
//! the IDCT work — and only the remainder goes through [`media::downscale`].

use libjpeg_turbo_rs::{Decoder, PixelFormat, ScalingFactor};
use media::{err, Image, Result};

pub fn decode(data: &[u8], max_w: u32, max_h: u32) -> Result<Image> {
    let mut d = Decoder::new(data).map_err(err)?;
    let (w, h) = (d.header().width as u32, d.header().height as u32);
    let (tw, th) = media::fit(w, h, max_w, max_h);
    // Smallest M/8 that still leaves at least the target size.
    if let Some(s) = (1..=8u32)
        .find(|m| w * m / 8 >= tw && h * m / 8 >= th)
        .filter(|&m| m < 8)
        .and_then(|m| ScalingFactor::try_new(m, 8).ok())
    {
        d.set_scale(s);
    }
    d.set_output_format(PixelFormat::Rgba);
    d.set_lenient(true);
    let img = d.decode_image().map_err(err)?.apply_orientation();
    let img = Image { width: img.width as u32, height: img.height as u32, rgba: img.data };
    Ok(media::downscale(img, max_w, max_h))
}

#[cfg(test)]
mod tests {
    #[test]
    fn decodes_and_scales() {
        let jpg = include_bytes!("../../testdata/photo.jpg");
        let full = super::decode(jpg, 0, 0).unwrap();
        assert_eq!(full.rgba.len(), (full.width * full.height * 4) as usize);
        let small = super::decode(jpg, 64, 64).unwrap();
        assert!(small.width <= 64 && small.height <= 64 && small.width.max(small.height) >= 63);
    }
}
