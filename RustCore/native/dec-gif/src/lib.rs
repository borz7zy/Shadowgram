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
    // Before the frame: the canvas size comes straight from the header.
    let mut rgba = media::rgba_buffer(w, h)?;
    let f = d.read_next_frame().map_err(err)?.ok_or_else(|| err("gif: no frames"))?;
    // Compose the (possibly partial) first frame onto a transparent canvas, clipped to it.
    let (w, h) = (w as usize, h as usize);
    let (fw, left, top) = (f.width as usize, f.left as usize, f.top as usize);
    let cw = fw.min(w.saturating_sub(left));
    if cw > 0 {
        for y in 0..(f.height as usize).min(h.saturating_sub(top)) {
            let (src, dst) = (y * fw * 4, ((top + y) * w + left) * 4);
            rgba[dst..dst + cw * 4].copy_from_slice(&f.buffer[src..src + cw * 4]);
        }
    }
    media::premultiply(&mut rgba);
    Ok(media::downscale(Image { width: w as u32, height: h as u32, rgba }, max_w, max_h))
}

#[cfg(test)]
mod tests {
    #[test]
    fn decodes() {
        let img = super::decode(include_bytes!("../../testdata/anim.gif"), 0, 0).unwrap();
        assert_eq!(img.rgba.len(), (img.width * img.height * 4) as usize);
    }

    /// A GIF of a `w×h` canvas with one 1×1 frame at `left`.
    fn gif(w: u16, h: u16, left: u16) -> Vec<u8> {
        let mut b = b"GIF89a".to_vec();
        b.extend(w.to_le_bytes());
        b.extend(h.to_le_bytes());
        b.extend([0x80, 0, 0, 0, 0, 0, 0xff, 0xff, 0xff, 0x2c]); // 2-colour table, image
        b.extend(left.to_le_bytes());
        b.extend([0, 0, 1, 0, 1, 0, 0, 0x02, 0x02, 0x44, 0x01, 0x00, 0x3b]);
        b
    }

    #[test]
    fn refuses_huge_canvas() {
        // 32768² × 4 wrapped to 0 in u32 and panicked on the first pixel
        assert!(super::decode(&gif(32768, 32768, 0), 0, 0).is_err());
    }

    #[test]
    fn clips_frame_outside_canvas() {
        let img = super::decode(&gif(4, 4, 100), 0, 0).unwrap();
        assert!(img.rgba.iter().all(|&c| c == 0));
        let img = super::decode(&gif(4, 4, 3), 0, 0).unwrap();
        assert_eq!(img.rgba[3 * 4 + 3], 255);
    }
}
