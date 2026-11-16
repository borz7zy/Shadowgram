// SPDX-FileCopyrightText: borz7zy
// SPDX-License-Identifier: MIT

//! 11-sharpen: guided upsampling of the processed low-res image onto the full-res denoised
//! luma (local affine model, He et al. guided filter / [C16]) + 2-level detail sharpening
//! with coring and an anti-halo clamp (11 §11.3, ASSUMED curves).

use crate::img::{par_rows, Img, BINOMIAL3, BINOMIAL5};

const R: usize = 2;
const EPS: f32 = 1e-4;

fn mul(a: &Img, b: &Img) -> Img {
    Img::from_fn(a.w, a.h, |x, y| a.d[y * a.w + x] * b.d[y * a.w + x])
}

/// out_c = a_c(p)·G_full(p) + b_c(p), with (a, b) fitted on (G_low, O_low,c).
pub fn guided_upsample(g_low: &Img, o_low: &[Img; 3], g_full: &Img) -> [Img; 3] {
    let mg = g_low.box_mean(R);
    let mgg = mul(g_low, g_low).box_mean(R);
    std::array::from_fn(|c| {
        let mo = o_low[c].box_mean(R);
        let mgo = mul(g_low, &o_low[c]).box_mean(R);
        let (w, h) = (g_low.w, g_low.h);
        let a = Img::from_fn(w, h, |x, y| {
            let i = y * w + x;
            let var = (mgg.d[i] - mg.d[i] * mg.d[i]).max(0.0);
            (mgo.d[i] - mg.d[i] * mo.d[i]) / (var + EPS)
        });
        let b = Img::from_fn(w, h, |x, y| {
            let i = y * w + x;
            mo.d[i] - a.d[i] * mg.d[i]
        });
        let a = a.box_mean(R).resize(g_full.w, g_full.h);
        let b = b.box_mean(R).resize(g_full.w, g_full.h);
        Img::from_fn(g_full.w, g_full.h, |x, y| {
            let i = y * g_full.w + x;
            a.d[i] * g_full.d[i] + b.d[i]
        })
    })
}

#[inline]
fn detail_curve(d: f32, gain: f32, core: f32) -> f32 {
    // coring around 0, linear gain, anti-halo clamp on large amplitudes
    (d.signum() * (d.abs() - core).max(0.0) * gain).clamp(-0.08, 0.08)
}

/// Luma-only 2-level sharpening in the output (gamma) domain; `core` ~ 1σ of residual noise.
pub fn sharpen(rgb: &mut [Img; 3], amount: f32, core: f32) {
    let y = Img::from_fn(rgb[0].w, rgb[0].h, |x, yy| {
        let i = yy * rgb[0].w + x;
        0.25 * rgb[0].d[i] + 0.5 * rgb[1].d[i] + 0.25 * rgb[2].d[i]
    });
    let b1 = y.blur(&BINOMIAL3);
    let b2 = b1.blur(&BINOMIAL5);
    let w = y.w;
    let delta = Img::from_fn(w, y.h, |x, yy| {
        let i = yy * w + x;
        detail_curve(y.d[i] - b1.d[i], amount, core) + detail_curve(b1.d[i] - b2.d[i], 0.5 * amount, core)
    });
    for ch in rgb.iter_mut() {
        par_rows(&mut ch.d, w, |yy, row| {
            for (x, v) in row.iter_mut().enumerate() {
                *v = (*v + delta.d[yy * w + x]).clamp(0.0, 1.0);
            }
        });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn guided_upsample_restores_edges() {
        let full = Img::from_fn(128, 64, |x, _| if x < 61 { 0.2 } else { 0.7 });
        let low = full.down2();
        let f = |g: f32| 0.1 + 1.2 * g; // processing in low-res
        let out_low = [0, 1, 2].map(|_| Img::from_fn(low.w, low.h, |x, y| f(low.d[y * low.w + x])));
        let up = guided_upsample(&low, &out_low, &full);
        let naive = out_low[0].resize(128, 64);
        let err = |img: &Img| (0..128).map(|x| (img.d[32 * 128 + x] - f(full.d[32 * 128 + x])).abs()).fold(0.0, f32::max);
        assert!(err(&up[0]) < 0.05, "guided {}", err(&up[0]));
        assert!(err(&naive) > 0.1);
    }

    #[test]
    fn sharpen_boosts_edges_not_small_noise() {
        let mut rgb = [0, 1, 2].map(|_| Img::from_fn(32, 8, |x, _| if x < 16 { 0.3 } else { 0.6 }));
        sharpen(&mut rgb, 1.0, 0.01);
        assert!(rgb[1].d[4 * 32 + 15] < 0.3 && rgb[1].d[4 * 32 + 16] > 0.6);
        let mut flat = [0, 1, 2].map(|_| Img::from_fn(32, 8, |x, _| 0.5 + if x % 2 == 0 { 0.004 } else { -0.004 }));
        let before = flat[0].d.clone();
        sharpen(&mut flat, 1.0, 0.01);
        assert_eq!(flat[0].d, before);
    }
}
