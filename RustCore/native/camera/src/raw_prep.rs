// SPDX-FileCopyrightText: borz7zy
// SPDX-License-Identifier: MIT

//! 02-raw-prep: RAW access in canonical RGGB, normalization, hot pixels, signal level / SNR.
//!
//! Units (REVIEW 1.7): every stage reads `x = (I - BL_c) / (WL - BL_c) * g_f`, no WB.
//! Noise model `Var = S*x + O` is in the same units (Camera2 SENSOR_NOISE_PROFILE).
//!
//! ponytail: BL correction estimate (02 §2.2) and row-artifact suppression (02 §2.4) are skipped;
//! SENSOR_DYNAMIC_BLACK_LEVEL is trusted. Add the closed-form δ* = Cov(yG,G)/Var(G) from
//! REVIEW.md if corners show a vignette-shaped cast in deep shadows.

use crate::img::{par_for, Img};
use std::sync::Mutex;

/// Canonical channel order: R, Gr (green on R rows), Gb, B.
pub const R: usize = 0;
pub const GR: usize = 1;
pub const GB: usize = 2;
pub const B: usize = 3;

/// One RAW16 frame viewed as 4 half-resolution planes in canonical RGGB order.
pub struct Frame<'a> {
    pub data: &'a mut [u16],
    /// Row stride in pixels.
    pub stride: usize,
    /// Offset of the first R pixel (crop to canonical RGGB).
    pub ox: usize,
    pub oy: usize,
    /// Plane (quad) size.
    pub pw: usize,
    pub ph: usize,
    /// Per canonical channel black level and scale = g_f / (WL - BL).
    pub bl: [f32; 4],
    pub scale: [f32; 4],
    /// Relative exposure gain g_f = TET_base / TET_f.
    pub gain: f32,
}

/// Camera2 SENSOR_INFO_COLOR_FILTER_ARRANGEMENT (RGGB=0, GRBG=1, GBRG=2, BGGR=3) -> R offset.
pub fn cfa_offset(cfa: i32) -> (usize, usize) {
    match cfa {
        1 => (1, 0),
        2 => (0, 1),
        3 => (1, 1),
        _ => (0, 0),
    }
}

/// Index into a raster-ordered 2x2 pattern (BLACK_LEVEL_PATTERN / DYNAMIC_BLACK_LEVEL order)
/// for canonical channel `c`.
pub fn raster_index(c: usize, ox: usize, oy: usize) -> usize {
    let (i, j) = (c & 1, c >> 1);
    ((i + ox) & 1) + 2 * ((j + oy) & 1)
}

/// Index into Camera2's [R, G_even, G_odd, B] vectors (COLOR_CORRECTION_GAINS, LSC map,
/// ASSUMED also SENSOR_NOISE_PROFILE) for canonical channel `c`.
pub fn rggb_vec_index(c: usize, oy: usize) -> usize {
    match c {
        GR => if oy == 0 { 1 } else { 2 },
        GB => if oy == 0 { 2 } else { 1 },
        c => c,
    }
}

impl<'a> Frame<'a> {
    pub fn new(
        data: &'a mut [u16],
        width: usize,
        height: usize,
        stride: usize,
        cfa: i32,
        bl_raster: [f32; 4],
        white_level: f32,
        gain: f32,
    ) -> Self {
        let (ox, oy) = cfa_offset(cfa);
        let mut bl = [0.0; 4];
        let mut scale = [0.0; 4];
        for c in 0..4 {
            bl[c] = bl_raster[raster_index(c, ox, oy)];
            scale[c] = gain / (white_level - bl[c]).max(1.0);
        }
        Frame { data, stride, ox, oy, pw: (width - ox) / 2, ph: (height - oy) / 2, bl, scale, gain }
    }

    /// Re-normalize for a new relative exposure gain g_f = TET_base / TET_f.
    pub fn set_gain(&mut self, gain: f32) {
        for v in self.scale.iter_mut() {
            *v *= gain / self.gain;
        }
        self.gain = gain;
    }

    /// RAW coordinates of plane sample (c, x, y).
    #[inline]
    pub fn raw_xy(&self, c: usize, x: usize, y: usize) -> (usize, usize) {
        (self.ox + 2 * x + (c & 1), self.oy + 2 * y + (c >> 1))
    }

    /// Normalized value of plane sample, coordinates clamped to the plane.
    #[inline]
    pub fn get(&self, c: usize, x: isize, y: isize) -> f32 {
        let x = x.clamp(0, self.pw as isize - 1) as usize;
        let y = y.clamp(0, self.ph as isize - 1) as usize;
        let (rx, ry) = self.raw_xy(c, x, y);
        (self.data[ry * self.stride + rx] as f32 - self.bl[c]) * self.scale[c]
    }

    /// Load a T x T tile of plane `c` at plane origin (x0, y0), clamped at the borders.
    pub fn load_tile<const T: usize>(&self, c: usize, x0: isize, y0: isize, buf: &mut [f32]) {
        let inside = x0 >= 0 && y0 >= 0 && x0 as usize + T <= self.pw && y0 as usize + T <= self.ph;
        if !inside {
            for j in 0..T {
                for i in 0..T {
                    buf[j * T + i] = self.get(c, x0 + i as isize, y0 + j as isize);
                }
            }
            return;
        }
        let (bl, sc) = (self.bl[c], self.scale[c]);
        for j in 0..T {
            let (rx, ry) = self.raw_xy(c, x0 as usize, y0 as usize + j);
            let row = &self.data[ry * self.stride + rx..];
            for i in 0..T {
                buf[j * T + i] = (row[2 * i] as f32 - bl) * sc;
            }
        }
    }

    /// Normalized value at canonical RAW coordinates (u, v) (0 = first R), clamped.
    /// Returns (value, channel).
    #[inline]
    pub fn get_raw(&self, u: isize, v: isize) -> (f32, usize) {
        let u = u.clamp(0, 2 * self.pw as isize - 1) as usize;
        let v = v.clamp(0, 2 * self.ph as isize - 1) as usize;
        let c = (u & 1) + 2 * (v & 1);
        let (rx, ry) = (self.ox + u, self.oy + v);
        ((self.data[ry * self.stride + rx] as f32 - self.bl[c]) * self.scale[c], c)
    }

    /// 04 §4.1: half-resolution gray = mean of the 2x2 quad.
    pub fn gray(&self) -> Img {
        let mut img = Img::new(self.pw, self.ph);
        crate::img::par_rows(&mut img.d, self.pw, |y, row| {
            let r0 = &self.data[(self.oy + 2 * y) * self.stride + self.ox..];
            let r1 = &self.data[(self.oy + 2 * y + 1) * self.stride + self.ox..];
            for (x, v) in row.iter_mut().enumerate() {
                let q = [r0[2 * x], r0[2 * x + 1], r1[2 * x], r1[2 * x + 1]];
                *v = 0.25 * (0..4).map(|c| (q[c] as f32 - self.bl[c]) * self.scale[c]).sum::<f32>();
            }
        });
        img
    }

    /// Plane `c` as an image.
    pub fn plane(&self, c: usize) -> Img {
        Img::from_fn(self.pw, self.ph, |x, y| self.get(c, x as isize, y as isize))
    }
}

/// 02 §2.1: MIPI RAW10 unpack (4 px = 5 bytes, byte 4 holds the 2 LSBs).
/// ponytail: unused on device — CameraX ImageCapture only delivers RAW_SENSOR (RAW16).
/// Call it from the JNI layer once a Camera2 RAW10 ImageReader path exists.
#[allow(dead_code)]
pub fn unpack_raw10(src: &[u8], width: usize, height: usize, row_stride: usize) -> Vec<u16> {
    let mut out = vec![0u16; width * height];
    for y in 0..height {
        let row = &src[y * row_stride..];
        for x in 0..width {
            let g = (x / 4) * 5;
            let k = x % 4;
            out[y * width + x] = ((row[g + k] as u16) << 2) | ((row[g + 4] >> (2 * k)) & 3) as u16;
        }
    }
    out
}

/// Hot-pixel detector threshold (02 §2.3): hot if v > m2 * A + B in WB-gained space.
/// A = 5.0 is the FACT default vector; B is ASSUMED.
const HOT_A: f32 = 5.0;
const HOT_B: f32 = 0.02;

fn is_hot(f: &Frame, wb: &[f32; 4], u: isize, v: isize) -> bool {
    let (val, c) = f.get_raw(u, v);
    let val = val * wb[c];
    if val >= 0.98 * wb[c] {
        return false; // clipped pixels are not candidates
    }
    // 8 nearest (any color) + 4 same-color at distance 2.
    const N: [(isize, isize); 12] = [
        (-1, -1), (0, -1), (1, -1), (-1, 0), (1, 0), (-1, 1), (0, 1), (1, 1),
        (-2, 0), (2, 0), (0, -2), (0, 2),
    ];
    let (mut m1, mut m2) = (f32::MIN, f32::MIN);
    for (dx, dy) in N {
        let (nv, nc) = f.get_raw(u + dx, v + dy);
        let nv = nv * wb[nc];
        if nv > m1 {
            m2 = m1;
            m1 = nv;
        } else if nv > m2 {
            m2 = nv;
        }
    }
    val > m2 * HOT_A + HOT_B
}

/// Detect hot pixels on base (frame 0), confirm in at least half of the other frames,
/// replace in every frame by the median of the 4 same-color neighbors. Returns the count.
pub fn suppress_hot_pixels(frames: &mut [Frame], wb: &[f32; 4]) -> usize {
    let base = &frames[0];
    let (w, h) = (2 * base.pw, 2 * base.ph);
    let found = Mutex::new(Vec::new());
    par_for(h, |v| {
        let row: Vec<(usize, usize)> = (0..w)
            .filter(|&u| is_hot(base, wb, u as isize, v as isize))
            .map(|u| (u, v))
            .collect();
        found.lock().unwrap().extend(row);
    });
    let others = frames.len() - 1;
    let hot: Vec<(usize, usize)> = found
        .into_inner()
        .unwrap()
        .into_iter()
        .filter(|&(u, v)| {
            let n = frames[1..].iter().filter(|f| is_hot(f, wb, u as isize, v as isize)).count();
            2 * n >= others
        })
        .collect();
    for f in frames.iter_mut() {
        for &(u, v) in &hot {
            let at = |du: isize, dv: isize| {
                let uu = (u as isize + du).clamp(0, w as isize - 1) as usize;
                let vv = (v as isize + dv).clamp(0, h as isize - 1) as usize;
                f.data[(f.oy + vv) * f.stride + f.ox + uu]
            };
            let mut n = [at(-2, 0), at(2, 0), at(0, -2), at(0, 2)];
            n.sort_unstable();
            f.data[(f.oy + v) * f.stride + f.ox + u] = ((n[1] as u32 + n[2] as u32) / 2) as u16;
        }
    }
    hot.len()
}

/// 02 §2.5: mean normalized green signal (subsampled).
pub fn mean_green(f: &Frame) -> f32 {
    let mut acc = 0.0f64;
    let mut n = 0usize;
    for y in (0..f.ph).step_by(4) {
        for x in (0..f.pw).step_by(4) {
            acc += 0.5 * (f.get(GR, x as isize, y as isize) + f.get(GB, x as isize, y as isize)) as f64;
            n += 1;
        }
    }
    (acc / n.max(1) as f64) as f32
}

/// 02 §2.5 ASSUMED(high): SNR = mu / sqrt(S*mu + O).
pub fn snr(mu: f32, s: f32, o: f32) -> f32 {
    let mu = mu.max(1e-6);
    mu / (s * mu + o).max(1e-12).sqrt()
}

/// HDR ratio from the metering frame (01 §1.6 p.4, ASSUMED): if the 99.5th percentile of green
/// is below 0.8, R = 1 (nothing to protect); otherwise R grows with the clipped fraction up to 8.
pub fn hdr_ratio(f: &Frame) -> f32 {
    let mut g = Vec::with_capacity(f.pw * f.ph / 16 + 1);
    for y in (0..f.ph).step_by(4) {
        for x in (0..f.pw).step_by(4) {
            g.push(f.get(GR, x as isize, y as isize).max(f.get(GB, x as isize, y as isize)));
        }
    }
    let n = g.len().max(1) as f32;
    let clipped = g.iter().filter(|&&v| v >= 0.98).count() as f32 / n;
    let idx = ((g.len() as f32 * 0.995) as usize).min(g.len().saturating_sub(1));
    let p995 = *g.select_nth_unstable_by(idx, |a, b| a.total_cmp(b)).1;
    if clipped > 0.001 {
        (2.0 + 600.0 * clipped).min(8.0)
    } else {
        (p995 / 0.8).clamp(1.0, 8.0)
    }
}

/// The 4 planes of a frame as images.
pub fn planes(f: &Frame) -> [Img; 4] {
    [f.plane(0), f.plane(1), f.plane(2), f.plane(3)]
}

/// Write a normalized value back as RAW (used by tests to build synthetic frames).
#[cfg(test)]
pub fn synth_raw(w: usize, h: usize, bl: f32, wl: f32, mut f: impl FnMut(usize, usize) -> f32) -> Vec<u16> {
    let mut v = vec![0u16; w * h];
    for y in 0..h {
        for x in 0..w {
            v[y * w + x] = (bl + f(x, y).clamp(0.0, 1.0) * (wl - bl)).round() as u16;
        }
    }
    v
}

#[cfg(test)]
pub fn noise(seed: &mut u64) -> f32 {
    // xorshift + Box-Muller-free approximation (sum of 4 uniforms, var = 1)
    let mut s = 0.0;
    for _ in 0..4 {
        *seed ^= *seed << 13;
        *seed ^= *seed >> 7;
        *seed ^= *seed << 17;
        s += (*seed >> 11) as f32 / (1u64 << 53) as f32;
    }
    (s - 2.0) * 3f32.sqrt()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn raw10_unpack() {
        // pixels 0x3FF, 0x001, 0x200, 0x155
        let p = [0x3FFu16, 0x001, 0x200, 0x155];
        let mut b = [0u8; 5];
        for k in 0..4 {
            b[k] = (p[k] >> 2) as u8;
            b[4] |= ((p[k] & 3) as u8) << (2 * k);
        }
        assert_eq!(unpack_raw10(&b, 4, 1, 5), p.to_vec());
    }

    #[test]
    fn cfa_mapping_bggr() {
        // BGGR: raster pattern [B, Gb, Gr, R]; canonical R must read raster index 3.
        let (ox, oy) = cfa_offset(3);
        assert_eq!(raster_index(R, ox, oy), 3);
        assert_eq!(raster_index(B, ox, oy), 0);
        assert_eq!(rggb_vec_index(GR, oy), 2); // R row is odd -> G_odd
        let mut raw = vec![0u16; 8 * 8];
        for y in 0..8 {
            for x in 0..8 {
                raw[y * 8 + x] = match (x & 1, y & 1) { (0, 0) => 100, (1, 1) => 900, _ => 500 };
            }
        }
        let f = Frame::new(&mut raw, 8, 8, 8, 3, [64.0; 4], 1023.0, 1.0);
        assert!(f.get(R, 0, 0) > f.get(GR, 0, 0) && f.get(GR, 0, 0) > f.get(B, 0, 0));
    }

    #[test]
    fn hot_pixel_removed() {
        let mut seed = 7u64;
        let mut frames_raw: Vec<Vec<u16>> = (0..3)
            .map(|_| synth_raw(64, 64, 64.0, 1023.0, |_, _| 0.05 + 0.002 * noise(&mut seed)))
            .collect();
        for r in frames_raw.iter_mut() {
            r[30 * 64 + 31] = 1000; // stuck pixel at the same place in every frame
        }
        let mut frames: Vec<Frame> = frames_raw
            .iter_mut()
            .map(|r| Frame::new(r, 64, 64, 64, 0, [64.0; 4], 1023.0, 1.0))
            .collect();
        let n = suppress_hot_pixels(&mut frames, &[1.0; 4]);
        assert_eq!(n, 1);
        let (v, _) = frames[1].get_raw(31, 30);
        assert!((v - 0.05).abs() < 0.01, "{v}");
    }

    #[test]
    fn hdr_ratio_bounds() {
        let mut dark = synth_raw(64, 64, 64.0, 1023.0, |_, _| 0.1);
        let mut bright = synth_raw(64, 64, 64.0, 1023.0, |x, _| if x < 16 { 1.0 } else { 0.3 });
        let fd = Frame::new(&mut dark, 64, 64, 64, 0, [64.0; 4], 1023.0, 1.0);
        let fb = Frame::new(&mut bright, 64, 64, 64, 0, [64.0; 4], 1023.0, 1.0);
        assert_eq!(hdr_ratio(&fd), 1.0);
        assert!(hdr_ratio(&fb) == 8.0);
        assert!((snr(0.25, 1e-4, 1e-6) - 0.25 / (0.25e-4f32 + 1e-6).sqrt()).abs() < 1e-3);
    }
}
