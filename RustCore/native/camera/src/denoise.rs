// SPDX-FileCopyrightText: borz7zy
// SPDX-License-Identifier: MIT

//! 08-denoise: noise-adaptive luma sigma filter (full-res) and multi-level chroma filter
//! (low-res branch), both driven by the merged noise model and the merge strength map.
//!
//! ponytail: moire detector (08 §8.2) and sky denoise (08 §8.5) are skipped — low confidence /
//! needs segmentation. Hook a moire term into `luma`/`chroma` strength if false color shows up.

use crate::img::{lerp, par_rows, plf, Img};

/// Finish tuning by log2(SNR) (08 §8.7 starting table, ASSUMED).
#[derive(Clone, Copy, Debug)]
pub struct Tune {
    pub luma_strength: f32,
    pub revert: f32,
    pub chroma_strength: f32,
    pub chroma_levels: usize,
    pub sharpen: f32,
}

pub fn tune(log2_snr: f32) -> Tune {
    let x = log2_snr;
    Tune {
        luma_strength: plf(&[(2.0, 2.0), (4.0, 1.4), (6.0, 1.0), (8.0, 0.7)], x),
        revert: plf(&[(2.0, 0.0), (4.0, 0.1), (6.0, 0.2), (8.0, 0.35)], x),
        chroma_strength: plf(&[(2.0, 4.0), (4.0, 2.5), (6.0, 1.5), (8.0, 1.0)], x),
        chroma_levels: plf(&[(2.0, 5.0), (4.0, 4.0), (6.0, 3.0), (8.0, 2.0)], x).round() as usize,
        // 11 §11.4 ASSUMED: sharpening falls linearly with log SNR, 0.3..1.0.
        sharpen: plf(&[(2.0, 0.3), (8.0, 1.0)], x),
    }
}

/// Carry the merged Bayer noise model into Y = R/4 + G/2 + B/4 after WB (07 §7.5).
/// Assumes x_c ~ Y / g_c (near-neutral) and ignores demosaic correlation (ASSUMED).
pub fn y_noise(s: &[f32; 4], o: &[f32; 4], wb: &[f32; 4]) -> (f32, f32) {
    // Y = (R + Gr + Gb + B) / 4 per quad: every sample has weight^2 = 1/16.
    let (mut sy, mut oy) = (0.0, 0.0);
    for c in 0..4 {
        sy += wb[c] * s[c] / 16.0;
        oy += wb[c] * wb[c] * o[c] / 16.0;
    }
    (sy, oy)
}

/// Luma sigma filter (08 §8.3), radius 2, two thresholds (1σ, 2σ) and revert.
/// `map` is the per-merge-tile variance multiplier sampled at (x / map_px, y / map_px).
pub fn luma(y: &Img, sy: f32, oy: f32, map: &Img, map_px: f32, strength: f32, revert: f32) -> Img {
    const R: isize = 2;
    const N_MIN: f32 = (R + 1) as f32;
    let mut out = Img::new(y.w, y.h);
    par_rows(&mut out.d, y.w, |py, row| {
        // Strength map row (bilinear in y once, linear in x per pixel).
        let fy = (py as f32 / map_px).clamp(0.0, (map.h - 1) as f32);
        let (m0, ay) = (fy.floor() as usize, fy - fy.floor());
        let m1 = (m0 + 1).min(map.h - 1);
        let mrow: Vec<f32> = (0..map.w).map(|i| map.d[m0 * map.w + i] * (1.0 - ay) + map.d[m1 * map.w + i] * ay).collect();
        let interior_y = py >= R as usize && py + (R as usize) < y.h;
        let rows: [&[f32]; 5] = std::array::from_fn(|j| {
            let yy = (py as isize + j as isize - R).clamp(0, y.h as isize - 1) as usize;
            &y.d[yy * y.w..(yy + 1) * y.w]
        });
        for (px, dst) in row.iter_mut().enumerate() {
            let c = rows[2][px];
            let fx = (px as f32 / map_px).min((map.w - 1) as f32);
            let i0 = fx as usize;
            let m = mrow[i0] + (mrow[(i0 + 1).min(map.w - 1)] - mrow[i0]) * (fx - i0 as f32);
            let s2 = (oy + c.max(0.0) * sy) * strength * m;
            let s2x4 = 4.0 * s2;
            let (mut s1, mut n1, mut s2sum, mut n2) = (0.0f32, 0.0f32, 0.0f32, 0.0f32);
            let interior = interior_y && px >= R as usize && px + (R as usize) < y.w;
            for r in rows.iter() {
                for dx in -R..=R {
                    let v = if interior {
                        r[(px as isize + dx) as usize]
                    } else {
                        r[(px as isize + dx).clamp(0, y.w as isize - 1) as usize]
                    };
                    let d2 = (c - v) * (c - v);
                    let p1 = (d2 <= s2) as u32 as f32;
                    let p2 = (d2 <= s2x4) as u32 as f32;
                    s1 += v * p1;
                    n1 += p1;
                    s2sum += v * p2;
                    n2 += p2;
                }
            }
            // The center counts once in both sums (it always passes).
            let f = if n1 > N_MIN { s1 / n1 } else { s2sum / n2 };
            *dst = lerp(f, c, revert);
        }
    });
    out
}

/// One level of the chroma cross filter (08 §8.4): sparse 5x5 window with step 2.
fn chroma_level(y: &Img, u: &Img, v: &Img, sy: f32, oy: f32, strength: f32) -> (Img, Img) {
    const R: isize = 2;
    const K: f32 = 0.5; // chroma-to-luma noise scale k_c (ASSUMED)
    let mut ou = Img::new(u.w, u.h);
    let mut ov = Img::new(u.w, u.h);
    let ovp = crate::img::SyncPtr(ov.d.as_mut_ptr());
    par_rows(&mut ou.d, u.w, |py, row| {
        for (px, dst) in row.iter_mut().enumerate() {
            let i = py * u.w + px;
            let (yc, uc, vc) = (y.d[i], u.d[i], v.d[i]);
            let s2 = (oy + yc.max(0.0) * sy) * strength;
            let (mut su1, mut sv1, mut n1, mut su2, mut sv2, mut n2) = (0.0, 0.0, 0.0f32, 0.0, 0.0, 0.0f32);
            let interior = px >= 4 && py >= 4 && px + 4 < u.w && py + 4 < u.h;
            for dy in -R..=R {
                for dx in -R..=R {
                    let (qx, qy) = (px as isize + 2 * dx, py as isize + 2 * dy);
                    let (yq, uq, vq) = if interior {
                        let q = qy as usize * u.w + qx as usize;
                        (y.d[q], u.d[q], v.d[q])
                    } else {
                        (y.at(qx, qy), u.at(qx, qy), v.at(qx, qy))
                    };
                    let d2 = (yc - yq).powi(2) + (K * (uc - uq)).powi(2) + (K * (vc - vq)).powi(2);
                    if d2 <= s2 {
                        su1 += uq;
                        sv1 += vq;
                        n1 += 1.0;
                    }
                    if d2 * 0.25 <= s2 {
                        su2 += uq;
                        sv2 += vq;
                        n2 += 1.0;
                    }
                }
            }
            let (fu, fv) = if n1 > R as f32 { (su1 / n1, sv1 / n1) } else { (su2 / n2, sv2 / n2) };
            *dst = fu;
            unsafe { *ovp.get().add(i) = fv };
        }
    });
    (ou, ov)
}

/// Multi-level chroma denoise: R_l = F(G_l - up(G_{l+1}) + up(R_{l+1})). `sy, oy` are the
/// luma noise model at the resolution of `y`.
pub fn chroma(y: &Img, u: &Img, v: &Img, sy: f32, oy: f32, strength: f32, levels: usize) -> (Img, Img) {
    let levels = levels.max(1);
    let mut py = vec![y.clone()];
    let mut pu = vec![u.clone()];
    let mut pv = vec![v.clone()];
    for _ in 1..levels {
        if py.last().unwrap().w < 16 || py.last().unwrap().h < 16 {
            break;
        }
        py.push(py.last().unwrap().down2());
        pu.push(pu.last().unwrap().down2());
        pv.push(pv.last().unwrap().down2());
    }
    let top = py.len() - 1;
    let scale = |l: usize| 0.25f32.powi(l as i32); // box 2x2 per level: variance / 4
    let (mut ru, mut rv) = chroma_level(&py[top], &pu[top], &pv[top], sy * scale(top), oy * scale(top), strength);
    for l in (0..top).rev() {
        let (w, h) = (pu[l].w, pu[l].h);
        let (gu, gv) = (pu[l + 1].resize(w, h), pv[l + 1].resize(w, h));
        let (uu, uv) = (ru.resize(w, h), rv.resize(w, h));
        let u_in = Img::from_fn(w, h, |x, y| pu[l].d[y * w + x] - gu.d[y * w + x] + uu.d[y * w + x]);
        let v_in = Img::from_fn(w, h, |x, y| pv[l].d[y * w + x] - gv.d[y * w + x] + uv.d[y * w + x]);
        (ru, rv) = chroma_level(&py[l], &u_in, &v_in, sy * scale(l), oy * scale(l), strength);
    }
    (ru, rv)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::raw_prep::noise;

    fn std(img: &Img, x0: usize, x1: usize) -> f32 {
        let vals: Vec<f32> = (0..img.h).flat_map(|y| (x0..x1).map(move |x| (x, y))).map(|(x, y)| img.d[y * img.w + x]).collect();
        let m = vals.iter().sum::<f32>() / vals.len() as f32;
        (vals.iter().map(|v| (v - m).powi(2)).sum::<f32>() / vals.len() as f32).sqrt()
    }

    #[test]
    fn luma_reduces_noise_keeps_edge() {
        let mut seed = 5u64;
        let sigma = 0.01f32;
        let mut y = Img::from_fn(64, 64, |x, _| if x < 32 { 0.2 } else { 0.6 });
        for v in y.d.iter_mut() {
            *v += sigma * noise(&mut seed);
        }
        let ones = Img::from_fn(4, 4, |_, _| 1.0);
        let out = luma(&y, 0.0, 2.0 * sigma * sigma, &ones, 16.0, 2.0, 0.0);
        assert!(std(&out, 2, 28) < 0.6 * std(&y, 2, 28));
        // Edge stays a step: the pixel next to the edge is not blended across.
        assert!(out.d[10 * 64 + 31] < 0.3 && out.d[10 * 64 + 32] > 0.5);
        let t = tune(3.0);
        assert!(t.luma_strength > 1.4 && t.luma_strength < 2.0);
        assert!(t.chroma_levels == 4 || t.chroma_levels == 5);
    }

    #[test]
    fn chroma_flattens_color_noise() {
        let mut seed = 11u64;
        let y = Img::from_fn(64, 64, |_, _| 0.3);
        let mut u = Img::from_fn(64, 64, |_, _| 0.05);
        for v in u.d.iter_mut() {
            *v += 0.03 * noise(&mut seed);
        }
        let v = u.clone();
        let (ou, _) = chroma(&y, &u, &v, 0.0, 0.03f32.powi(2), 4.0, 3);
        assert!(std(&ou, 0, 64) < 0.4 * std(&u, 0, 64), "{} vs {}", std(&ou, 0, 64), std(&u, 0, 64));
        assert!((ou.mean() - 0.05).abs() < 0.01);
    }
}
