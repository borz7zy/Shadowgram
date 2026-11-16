// SPDX-FileCopyrightText: borz7zy
// SPDX-License-Identifier: MIT

//! PNG via `zune-png` — the fastest of the benchmarked Rust/C PNG decoders.

use media::{err, Image, Result};
use zune_core::colorspace::ColorSpace;
use zune_core::options::DecoderOptions;
use zune_png::PngDecoder;

pub fn decode(data: &[u8], max_w: u32, max_h: u32) -> Result<Image> {
    let opts = DecoderOptions::default().png_set_add_alpha_channel(true).png_set_strip_to_8bit(true);
    let mut d = PngDecoder::new_with_options(zune_core::bytestream::ZCursor::new(data), opts);
    d.decode_headers().map_err(err)?;
    let (w, h) = d.dimensions().ok_or_else(|| err("png: no dimensions"))?;
    let cs = d.colorspace().ok_or_else(|| err("png: no colorspace"))?;
    let px = d.decode_raw().map_err(err)?;
    let mut rgba = match cs {
        ColorSpace::RGBA => px,
        ColorSpace::LumaA => px.chunks_exact(2).flat_map(|p| [p[0], p[0], p[0], p[1]]).collect(),
        other => return Err(err(format!("png: unexpected colorspace {other:?}"))),
    };
    media::premultiply(&mut rgba);
    Ok(media::downscale(Image { width: w as u32, height: h as u32, rgba }, max_w, max_h))
}

#[cfg(test)]
mod tests {
    #[test]
    fn decodes() {
        let img = super::decode(include_bytes!("../../testdata/alpha.png"), 0, 0).unwrap();
        assert_eq!(img.rgba.len(), (img.width * img.height * 4) as usize);
        assert!(img.rgba.chunks_exact(4).any(|p| p[3] < 255), "alpha must survive");
    }
}
