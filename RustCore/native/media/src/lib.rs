// SPDX-FileCopyrightText: borz7zy
// SPDX-License-Identifier: MIT

//! Shared bits of the media decoders: what a file is ([`detect`]), the decoded
//! [`Image`] every `dec-*` crate returns, and the post-decode fit-into-box
//! [`downscale`].

/// What a Telegram file is and who decodes it. Values cross JNI — keep in
/// sync with `RustMedia.Kind` on the Kotlin side.
#[repr(i32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MediaKind {
    Unknown = 0,
    Jpeg = 1,
    Png = 2,
    Webp = 3,
    Gif = 4,
    /// Matroska/WebM — Telegram video stickers and custom emoji (VP9 + alpha).
    Webm = 5,
    /// MP4/MOV, Ogg, MP3, … — hardware MediaCodec via ExoPlayer does it better.
    Platform = 6,
    /// gzip'd Lottie — Telegram animated stickers and emoji (rlottie).
    Tgs = 7,
}

impl MediaKind {
    /// Decoded by one of our crates (as opposed to the platform / nobody).
    pub fn is_ours(self) -> bool {
        matches!(self, Self::Jpeg | Self::Png | Self::Webp | Self::Gif | Self::Webm | Self::Tgs)
    }
}

/// Sniff magic bytes; the first 16 bytes of a file are enough.
pub fn detect(b: &[u8]) -> MediaKind {
    use MediaKind::*;
    let at = |off: usize, sig: &[u8]| b.len() >= off + sig.len() && &b[off..off + sig.len()] == sig;
    if at(0, &[0xFF, 0xD8, 0xFF]) {
        Jpeg
    } else if at(0, b"\x89PNG\r\n\x1a\n") {
        Png
    } else if at(0, b"RIFF") && at(8, b"WEBP") {
        Webp
    } else if at(0, b"GIF87a") || at(0, b"GIF89a") {
        Gif
    } else if at(0, &[0x1A, 0x45, 0xDF, 0xA3]) {
        Webm
    } else if at(0, &[0x1F, 0x8B]) {
        Tgs
    } else if at(4, b"ftyp") || at(0, b"OggS") || at(0, b"ID3") || at(0, b"fLaC") || at(0, &[0xFF, 0xFB]) || at(0, &[0xFF, 0xF1]) || at(0, &[0xFF, 0xF9]) {
        Platform
    } else {
        Unknown
    }
}

/// Tightly packed 8-bit RGBA, **premultiplied** alpha — byte-for-byte what an
/// Android `ARGB_8888` bitmap holds.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Image {
    pub width: u32,
    pub height: u32,
    pub rgba: Vec<u8>,
}

#[derive(Debug)]
pub struct Error(pub String);

impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for Error {}

pub type Result<T> = std::result::Result<T, Error>;

pub fn err(e: impl std::fmt::Display) -> Error {
    Error(e.to_string())
}

/// Most pixels a decoder allocates for one image (64 MiB of RGBA).
pub const MAX_PIXELS: usize = 1 << 24;

/// A zeroed RGBA buffer for `w×h`, refused past [`MAX_PIXELS`] instead of overflowing or OOMing.
pub fn rgba_buffer(w: u32, h: u32) -> Result<Vec<u8>> {
    match (w as usize).checked_mul(h as usize) {
        Some(px) if px <= MAX_PIXELS => Ok(vec![0u8; px * 4]),
        _ => Err(err(format!("image too large: {w}x{h}"))),
    }
}

/// Size that fits `w×h` into `max_w×max_h` keeping aspect, never upscaling.
/// A zero bound means "no limit" on that axis.
pub fn fit(w: u32, h: u32, max_w: u32, max_h: u32) -> (u32, u32) {
    let sx = if max_w == 0 { 1.0 } else { max_w as f64 / w as f64 };
    let sy = if max_h == 0 { 1.0 } else { max_h as f64 / h as f64 };
    let s = sx.min(sy).min(1.0);
    (((w as f64 * s).round() as u32).max(1), ((h as f64 * s).round() as u32).max(1))
}

/// Area-average shrink into the [`fit`] box. No-op when it already fits.
pub fn downscale(img: Image, max_w: u32, max_h: u32) -> Image {
    let (dw, dh) = fit(img.width, img.height, max_w, max_h);
    if (dw, dh) == (img.width, img.height) {
        return img;
    }
    let (sw, sh) = (img.width as usize, img.height as usize);
    let (dw, dh) = (dw as usize, dh as usize);
    let mut out = vec![0u8; dw * dh * 4];
    for dy in 0..dh {
        let (y0, y1) = (dy * sh / dh, ((dy + 1) * sh / dh).max(dy * sh / dh + 1));
        for dx in 0..dw {
            let (x0, x1) = (dx * sw / dw, ((dx + 1) * sw / dw).max(dx * sw / dw + 1));
            let mut acc = [0u32; 4];
            for y in y0..y1 {
                let row = &img.rgba[(y * sw + x0) * 4..(y * sw + x1) * 4];
                for px in row.chunks_exact(4) {
                    for c in 0..4 {
                        acc[c] += px[c] as u32;
                    }
                }
            }
            let n = ((y1 - y0) * (x1 - x0)) as u32;
            let o = (dy * dw + dx) * 4;
            for c in 0..4 {
                out[o + c] = ((acc[c] + n / 2) / n) as u8;
            }
        }
    }
    Image { width: dw as u32, height: dh as u32, rgba: out }
}

/// Straight → premultiplied alpha, in place.
pub fn premultiply(rgba: &mut [u8]) {
    for px in rgba.chunks_exact_mut(4) {
        let a = px[3] as u32;
        if a != 255 {
            for c in &mut px[..3] {
                *c = ((*c as u32 * a + 127) / 255) as u8;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn detects_magic() {
        assert_eq!(detect(&[0xFF, 0xD8, 0xFF, 0xE0]), MediaKind::Jpeg);
        assert_eq!(detect(b"\x89PNG\r\n\x1a\n...."), MediaKind::Png);
        assert_eq!(detect(b"RIFF\0\0\0\0WEBPVP8 "), MediaKind::Webp);
        assert_eq!(detect(b"GIF89a"), MediaKind::Gif);
        assert_eq!(detect(&[0x1A, 0x45, 0xDF, 0xA3, 0x9F]), MediaKind::Webm);
        assert_eq!(detect(&[0x1F, 0x8B, 0x08]), MediaKind::Tgs);
        assert_eq!(detect(b"\0\0\0\x20ftypisom"), MediaKind::Platform);
        assert_eq!(detect(b"OggS"), MediaKind::Platform);
        assert_eq!(detect(b"hi"), MediaKind::Unknown);
    }

    #[test]
    fn fit_and_downscale() {
        assert_eq!(fit(400, 200, 100, 100), (100, 50));
        assert_eq!(fit(50, 50, 100, 100), (50, 50));
        assert_eq!(fit(400, 200, 0, 0), (400, 200));
        // 4×2 with left half white, right half black → 2×1 white, black.
        let mut rgba = Vec::new();
        for _ in 0..2 {
            rgba.extend_from_slice(&[255; 8]);
            rgba.extend_from_slice(&[0, 0, 0, 255, 0, 0, 0, 255]);
        }
        let out = downscale(Image { width: 4, height: 2, rgba }, 2, 2);
        assert_eq!((out.width, out.height), (2, 1));
        assert_eq!(out.rgba, vec![255, 255, 255, 255, 0, 0, 0, 255]);
    }

    #[test]
    fn premultiplies() {
        let mut px = [200, 100, 0, 128];
        premultiply(&mut px);
        assert_eq!(px, [100, 50, 0, 128]);
    }
}
