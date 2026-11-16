// SPDX-FileCopyrightText: borz7zy
// SPDX-License-Identifier: MIT

//! Real GIFs (rare — Telegram converts most to MP4) via the `gif` crate.
//! First frame only; animated playback is the platform's job once it's MP4.

use media::{err, Image, Result};

pub fn decode(data: &[u8], max_w: u32, max_h: u32) -> Result<Image> {
    let mut o = gif::DecodeOptions::new();
    o.set_color_output(gif::ColorOutput::RGBA);
    let mut d = o.read_info(data).map_err(err)?;
    let (w, h) = (d.width() as u32, d.height() as u32);
    let f = d.read_next_frame().map_err(err)?.ok_or_else(|| err("gif: no frames"))?;
    // Compose the (possibly partial) first frame onto a transparent canvas.
    let mut rgba = vec![0u8; (w * h * 4) as usize];
    for y in 0..f.height as u32 {
        let (cy, src) = (f.top as u32 + y, (y * f.width as u32 * 4) as usize);
        if cy >= h {
            break;
        }
        let cw = (f.width as u32).min(w.saturating_sub(f.left as u32));
        let dst = ((cy * w + f.left as u32) * 4) as usize;
        rgba[dst..dst + (cw * 4) as usize].copy_from_slice(&f.buffer[src..src + (cw * 4) as usize]);
    }
    media::premultiply(&mut rgba);
    Ok(media::downscale(Image { width: w, height: h, rgba }, max_w, max_h))
}

#[cfg(test)]
mod tests {
    #[test]
    fn decodes() {
        let img = super::decode(include_bytes!("../../testdata/anim.gif"), 0, 0).unwrap();
        assert_eq!(img.rgba.len(), (img.width * img.height * 4) as usize);
    }
}
