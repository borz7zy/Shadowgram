// SPDX-FileCopyrightText: borz7zy
// SPDX-License-Identifier: MIT

//! 09-tonemap (low-res branch, linear sRGB in, sRGB-encoded out):
//! synthetic exposures + Mertens exposure fusion on Laplacian pyramids (09 §9.3),
//! histogram dehaze (09 §9.4), global curve = sRGB OETF + S-curve (09 §9.5, sign fixed —
//! REVIEW 1.1).
//!
//! ponytail: no partial fusion at the pyramid top, no local-contrast map, no DHS flag
//! (09 §9.3 UNKNOWN). Add the top-level lerp if fusion shifts global tone too much.

use crate::color::{srgb_eotf, srgb_oetf};
use crate::img::{par_rows, smoothstep, Img};

fn luma709(rgb: &[Img; 3]) -> Img {
    Img::from_fn(rgb[0].w, rgb[0].h, |x, y| {
        let i = y * rgb[0].w + x;
        0.2126 * rgb[0].d[i] + 0.7152 * rgb[1].d[i] + 0.0722 * rgb[2].d[i]
    })
}

fn map(img: &Img, f: impl Fn(f32) -> f32 + Sync) -> Img {
    Img::from_fn(img.w, img.h, |x, y| f(img.d[y * img.w + x]))
}

fn gauss_pyr(img: Img, n: usize) -> Vec<Img> {
    let mut p = vec![img];
    for _ in 1..n {
        let next = p.last().unwrap().pyr_down();
        p.push(next);
    }
    p
}

fn lap_pyr(img: Img, n: usize) -> Vec<Img> {
    let g = gauss_pyr(img, n);
    let mut l: Vec<Img> = (0..n - 1)
        .map(|i| {
            let up = g[i + 1].resize(g[i].w, g[i].h);
            Img::from_fn(g[i].w, g[i].h, |x, y| g[i].d[y * g[i].w + x] - up.d[y * g[i].w + x])
        })
        .collect();
    l.push(g[n - 1].clone());
    l
}

/// Weight LUTs (09 §9.2, ASSUMED): longest exposure keeps weight 1 through midtones and
/// falls between the FACT constants 0.509 .. 0.9608; others use Mertens well-exposedness.
fn weight(t: f32, longest: bool) -> f32 {
    1e-3 + if longest {
        1.0 - smoothstep(0.509, 0.960784, t)
    } else {
        (-(t - 0.5) * (t - 0.5) / (2.0 * 0.2 * 0.2)).exp()
    }
}

/// Local tonemap: synthesize exposures [1, (√R), R] from the short-TET merge and fuse.
pub fn fuse(rgb: &mut [Img; 3], r_hdr: f32) {
    if r_hdr <= 1.01 {
        return; // 09 §9.6: ratio ~1 -> identity
    }
    let gains: Vec<f32> = if r_hdr >= 2.0 { vec![1.0, r_hdr.sqrt(), r_hdr] } else { vec![1.0, r_hdr] };
    let y = luma709(rgb);
    let levels = ((y.w.min(y.h) as f32 / 8.0).log2().floor() as usize).clamp(1, 8);
    let ts: Vec<Img> = gains.iter().map(|&g| map(&y, |v| srgb_oetf(v * g))).collect();
    let mut ws: Vec<Img> = ts.iter().enumerate().map(|(i, t)| map(t, |v| weight(v, i == gains.len() - 1))).collect();
    let total = Img::from_fn(y.w, y.h, |x, yy| ws.iter().map(|w| w.d[yy * y.w + x]).sum());
    for w in ws.iter_mut() {
        for (v, t) in w.d.iter_mut().zip(total.d.iter()) {
            *v /= t;
        }
    }
    let mut acc: Option<Vec<Img>> = None;
    for (t, w) in ts.into_iter().zip(ws) {
        let lp = lap_pyr(t, levels);
        let gp = gauss_pyr(w, levels);
        let term: Vec<Img> = lp
            .iter()
            .zip(gp.iter())
            .map(|(l, g)| Img::from_fn(l.w, l.h, |x, y| l.d[y * l.w + x] * g.d[y * l.w + x]))
            .collect();
        acc = Some(match acc {
            None => term,
            Some(a) => a
                .into_iter()
                .zip(term)
                .map(|(mut a, t)| {
                    a.d.iter_mut().zip(t.d).for_each(|(a, t)| *a += t);
                    a
                })
                .collect(),
        });
    }
    let acc = acc.unwrap();
    let mut out = acc[levels - 1].clone();
    for l in (0..levels - 1).rev() {
        let up = out.resize(acc[l].w, acc[l].h);
        out = Img::from_fn(acc[l].w, acc[l].h, |x, y| acc[l].d[y * acc[l].w + x] + up.d[y * acc[l].w + x]);
    }
    // Transfer tone to color: RGB *= Y_out_lin / Y_lin (09 §9.3).
    for ch in rgb.iter_mut() {
        let w = ch.w;
        par_rows(&mut ch.d, w, |yy, row| {
            for (x, v) in row.iter_mut().enumerate() {
                let i = yy * w + x;
                let ratio = srgb_eotf(out.d[i]) / y.d[i].max(1e-5);
                *v *= ratio.clamp(0.0, r_hdr);
            }
        });
    }
}

/// Histogram dehaze (09 §9.4, ASSUMED): black point = 0.1% percentile of min(R,G,B), capped.
pub fn dehaze(rgb: &mut [Img; 3]) {
    let mut mins: Vec<f32> = (0..rgb[0].d.len())
        .step_by(7)
        .map(|i| rgb[0].d[i].min(rgb[1].d[i]).min(rgb[2].d[i]))
        .collect();
    if mins.is_empty() {
        return;
    }
    let k = mins.len() / 1000;
    let b = mins.select_nth_unstable_by(k, f32::total_cmp).1.clamp(0.0, 0.02);
    for ch in rgb.iter_mut() {
        ch.d.iter_mut().for_each(|v| *v = ((*v - b) / (1.0 - b)).max(0.0));
    }
}

/// Contrast S-curve, slope 1 + c/2 at 0.5 (REVIEW 1.1).
pub fn s_curve(y: f32, c: f32) -> f32 {
    y + c * y * (1.0 - y) * (2.0 * y - 1.0)
}

/// Global tonemap per channel: brightness, sRGB OETF, S-curve. Night (12 §12.6, [L19]):
/// darker and more contrast so night stays night (ASSUMED values).
pub fn global(rgb: &mut [Img; 3], night: bool) {
    let (gain, c) = if night { (0.8, 0.25) } else { (1.0, 0.15) };
    for ch in rgb.iter_mut() {
        let w = ch.w;
        par_rows(&mut ch.d, w, |_, row| {
            for v in row.iter_mut() {
                *v = s_curve(srgb_oetf(*v * gain), c).clamp(0.0, 1.0);
            }
        });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fusion_lifts_shadows_keeps_highlights() {
        let (w, h) = (512, 128);
        let lin = |x: usize| if x < 256 { 0.02 } else { 0.6 };
        let mut rgb = [0, 1, 2].map(|_| Img::from_fn(w, h, move |x, _| lin(x)));
        fuse(&mut rgb, 4.0);
        let dark = rgb[1].d[64 * w + 40] / 0.02;
        let bright = rgb[1].d[64 * w + 470] / 0.6;
        assert!(dark > 2.0 && dark <= 4.0, "dark gain {dark}");
        assert!(bright < 1.3, "bright gain {bright}");
        assert!(s_curve(0.25, 0.15) < 0.25 && s_curve(0.75, 0.15) > 0.75);
        assert!((s_curve(0.0, 0.2)).abs() < 1e-7 && (s_curve(1.0, 0.2) - 1.0).abs() < 1e-7);
    }
}
