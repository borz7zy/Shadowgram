// SPDX-FileCopyrightText: borz7zy
// SPDX-License-Identifier: MIT

//! Moire / false-color detector and suppression (08 §8.2, 99.2 p.9 — UNKNOWN in the spec, so the
//! detector is our own, ASSUMED).
//!
//! Idea: aliasing of a texture near the CFA Nyquist produces chroma whose beat pattern moves much
//! faster than the scene when the camera shifts by a fraction of a pixel, so the block-average
//! chroma of aligned frames disagrees between frames. True color is consistent. A block is
//! flagged when (a) chroma is inconsistent across frames well above the noise of the block mean
//! and (b) it is textured (green variance >> noise) — flat moving areas do not qualify, and frames
//! whose block luma differs from the base (motion) are left out of the statistics.
//!
//! In flagged blocks:
//! - MFSR kernels are forced to the wide "blurred" shape, so every output pixel averages many
//!   frames/phases like the Wiener merge does (06 §6.3 kernel, see `superres`);
//! - the finish replaces pixel chromaticity with the multi-frame mean chromaticity of the block,
//!   where the beat phases average out (luma is kept).

use crate::align::Field;
use crate::img::{par_for, smoothstep, Img, SyncPtr};
use crate::raw_prep::{Frame, B, GB, GR, R};

/// Block size in quads (= 16 RAW px), nodes at quad 8*i + 4.
pub const BLOCK: usize = 8;

pub struct MoireMap {
    /// Moire strength in [0, 1] per block.
    pub m: Img,
    /// Multi-frame mean chromaticity (r, g, b sum to 1) per block, WB applied.
    pub chroma: [Img; 3],
}

/// Mean WB'd (R, G, B) of the block at node (bx, by) of frame f, shifted by `d` quads,
/// plus the variance of its green samples.
fn block_stats(f: &Frame, wb: &[f32; 4], bx: usize, by: usize, d: [isize; 2]) -> ([f32; 3], f32) {
    let (x0, y0) = ((bx * BLOCK) as isize + d[0], (by * BLOCK) as isize + d[1]);
    let (mut sum, mut g1, mut g2) = ([0.0f32; 3], 0.0f32, 0.0f32);
    for j in 0..BLOCK as isize {
        for i in 0..BLOCK as isize {
            let v = |c: usize| f.get(c, x0 + i, y0 + j);
            let (gr, gb) = (v(GR), v(GB));
            sum[0] += v(R) * wb[R];
            sum[1] += 0.5 * (gr * wb[GR] + gb * wb[GB]);
            sum[2] += v(B) * wb[B];
            g1 += gr + gb;
            g2 += gr * gr + gb * gb;
        }
    }
    let n = (BLOCK * BLOCK) as f32;
    let gm = g1 / (2.0 * n);
    (sum.map(|v| v / n), (g2 / (2.0 * n) - gm * gm).max(0.0))
}

/// `frames[0]` is the base; `fields[f-1]` aligns frame f (plane = quad pixels).
pub fn detect(frames: &[Frame], fields: &[Field], wb: &[f32; 4], s: &[f32; 4], o: &[f32; 4]) -> MoireMap {
    let base = &frames[0];
    let (nbx, nby) = (base.pw.div_ceil(BLOCK), base.ph.div_ceil(BLOCK));
    let mut m = Img::new(nbx, nby);
    let mut chroma: [Img; 3] = std::array::from_fn(|_| Img::new(nbx, nby));
    let (sg, og) = (0.5 * (s[GR] + s[GB]), 0.5 * (o[GR] + o[GB]));
    let wmax = wb.iter().cloned().fold(1.0f32, f32::max);
    let mp = SyncPtr(m.d.as_mut_ptr());
    let cp: [SyncPtr<f32>; 3] = std::array::from_fn(|c| SyncPtr(chroma[c].d.as_mut_ptr()));
    par_for(nbx * nby, |b| {
        let (bx, by) = (b % nbx, b / nbx);
        let center = [(bx * BLOCK + BLOCK / 2) as f32, (by * BLOCK + BLOCK / 2) as f32];
        let (rgb0, gvar) = block_stats(base, wb, bx, by, [0, 0]);
        let lum0 = rgb0.iter().sum::<f32>().max(1e-6);
        let mu = rgb0[1] / wb[GR].max(1e-6);
        let noise_g = (sg * mu.max(0.0) + og).max(1e-12);
        // Noise of a block-mean chromaticity component (64 quads), with a floor (ASSUMED).
        let sigma_c = ((noise_g / (BLOCK * BLOCK) as f32).sqrt() * wmax / lum0).max(0.004);
        let mut cs: Vec<[f32; 3]> = Vec::with_capacity(frames.len());
        cs.push(rgb0.map(|v| v / lum0));
        for (f, fr) in frames.iter().enumerate().skip(1) {
            let v = fields[f - 1].at_px(center[0], center[1]);
            let (rgb, _) = block_stats(fr, wb, bx, by, [v[0].round() as isize, v[1].round() as isize]);
            let lum = rgb.iter().sum::<f32>();
            // Motion (or misalignment) changes the block luma; moire mostly moves chroma.
            if (lum - lum0).abs() > 0.1 * lum0 + 4.0 * sigma_c * lum0 {
                continue;
            }
            cs.push(rgb.map(|v| v / lum.max(1e-6)));
        }
        let n = cs.len() as f32;
        let mean: [f32; 3] = std::array::from_fn(|c| cs.iter().map(|v| v[c]).sum::<f32>() / n);
        let incons = if cs.len() < 3 {
            0.0
        } else {
            (cs.iter().map(|v| (v[0] - mean[0]).powi(2) + (v[2] - mean[2]).powi(2)).sum::<f32>() / n).sqrt()
        };
        let texture = smoothstep(4.0, 16.0, gvar / noise_g);
        let strength = smoothstep(2.0, 5.0, incons / sigma_c) * texture;
        unsafe {
            *mp.get().add(b) = strength;
            for c in 0..3 {
                *cp[c].get().add(b) = mean[c];
            }
        }
    });
    MoireMap { m, chroma }
}

impl MoireMap {
    /// Moire strength at quad (plane) coordinates.
    pub fn at_quad(&self, x: f32, y: f32) -> f32 {
        let half = (BLOCK / 2) as f32;
        self.m.sample((x - half) / BLOCK as f32, (y - half) / BLOCK as f32)
    }

    /// Replace chromaticity by the multi-frame block mean where moire is detected (full-res
    /// WB'd camera RGB; luma R+G+B kept).
    pub fn suppress(&self, rgb: &mut [Img; 3]) {
        if self.m.d.iter().all(|&v| v <= 0.0) {
            return;
        }
        let (w, h) = (rgb[0].w, rgb[0].h);
        let ptrs: [SyncPtr<f32>; 3] = std::array::from_fn(|c| SyncPtr(rgb[c].d.as_mut_ptr()));
        let half = (BLOCK / 2) as f32;
        par_for(h, |y| {
            let by = (y as f32 * 0.5 - half) / BLOCK as f32;
            for x in 0..w {
                let bx = (x as f32 * 0.5 - half) / BLOCK as f32;
                let t = self.m.sample(bx, by);
                if t <= 0.0 {
                    continue;
                }
                let i = y * w + x;
                unsafe {
                    let p = ptrs.map(|p| p.get().add(i));
                    let sum = *p[0] + *p[1] + *p[2];
                    for c in 0..3 {
                        let target = self.chroma[c].sample(bx, by) * sum;
                        *p[c] += (target - *p[c]) * t;
                    }
                }
            }
        });
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::raw_prep::synth_raw;
    use crate::superres::superres;

    /// Mean false color (|R-G| + |B-G|) / G of a neutral scene, inner region.
    fn false_color(rgb: &[Img; 3]) -> f32 {
        let (w, h) = (rgb[0].w, rgb[0].h);
        let (mut fc, mut g) = (0.0f32, 0.0f32);
        for y in 16..h - 16 {
            for x in 16..w - 16 {
                let i = y * w + x;
                fc += (rgb[0].d[i] - rgb[1].d[i]).abs() + (rgb[2].d[i] - rgb[1].d[i]).abs();
                g += rgb[1].d[i];
            }
        }
        fc / g
    }

    #[test]
    fn periodic_grid_moire_is_reduced() {
        // Neutral (gray) "monitor grid": fine grating near the CFA Nyquist, slightly rotated,
        // shot with sub-pixel camera shifts; the alignment is aliased (off by ±0.5 px).
        let (w, h, n) = (192usize, 192usize, 8usize);
        let grid = |x: f32, y: f32| {
            let (u, v) = (x * 0.995 + y * 0.1, y * 0.995 - x * 0.1);
            0.35 + 0.25 * (u * 2.0 * std::f32::consts::PI / 2.15).cos() * (v * 2.0 * std::f32::consts::PI / 2.3).cos()
        };
        let shifts: Vec<[f32; 2]> = (0..n).map(|f| [0.37 * f as f32 % 2.0, 0.61 * f as f32 % 2.0]).collect();
        let mut raws: Vec<Vec<u16>> = shifts
            .iter()
            .map(|s| synth_raw(w, h, 0.0, 65535.0, |x, y| grid(x as f32 - s[0], y as f32 - s[1])))
            .collect();
        let frames: Vec<Frame> = raws.iter_mut().map(|r| Frame::new(r, w, h, w, 0, [0.0; 4], 65535.0, 1.0)).collect();
        let fields: Vec<Field> = shifts[1..]
            .iter()
            .enumerate()
            .map(|(i, s)| {
                let err = if i % 2 == 0 { 0.5 } else { -0.5 };
                let (nx, ny) = (w.div_ceil(16), h.div_ceil(16));
                Field { nx, ny, step: 8, tile: 16, v: vec![[(s[0] + err) / 2.0, (s[1] - err) / 2.0]; nx * ny] }
            })
            .collect();
        let (s, o) = ([1e-5f32; 4], [1e-7f32; 4]);
        let wb = [1.0f32; 4];

        let (before, _) = superres(&frames, &fields, &wb, &s, &o, None);
        let map = detect(&frames, &fields, &wb, &s, &o);
        assert!(map.m.mean() > 0.5, "detector should fire on the grid: {}", map.m.mean());
        let (mut after, _) = superres(&frames, &fields, &wb, &s, &o, Some(&map));
        map.suppress(&mut after);
        let (fb, fa) = (false_color(&before), false_color(&after));
        eprintln!("moire map {:.3}, false color before {fb:.4} after {fa:.4}", map.m.mean());
        assert!(fa < 0.5 * fb, "false color before {fb:.4} after {fa:.4}");

        // A smooth colored, static scene must not be flagged.
        let mut flat: Vec<Vec<u16>> = (0..4)
            .map(|_| synth_raw(64, 64, 0.0, 65535.0, |x, y| [0.5, 0.3, 0.3, 0.1][(x & 1) + 2 * (y & 1)]))
            .collect();
        let ff: Vec<Frame> = flat.iter_mut().map(|r| Frame::new(r, 64, 64, 64, 0, [0.0; 4], 65535.0, 1.0)).collect();
        let zf: Vec<Field> = (0..3).map(|_| Field { nx: 4, ny: 4, step: 8, tile: 16, v: vec![[0.0; 2]; 16] }).collect();
        assert_eq!(detect(&ff, &zf, &wb, &s, &o).m.mean(), 0.0);
    }
}
