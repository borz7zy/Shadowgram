// SPDX-FileCopyrightText: borz7zy
// SPDX-License-Identifier: MIT

//! 10-color: AWB fallback, LSC + CCM (low-res branch), sRGB transfer functions.
//!
//! AWB: FFCC weights are UNKNOWN (10 §10.1), so the HAL's AWB gains (COLOR_CORRECTION_GAINS,
//! AWB locked for the burst) are used; gray-world on the 64x48 statistics grid is the fallback.
//! CCM: COLOR_CORRECTION_TRANSFORM (WB'd sensor RGB -> linear sRGB) — REVIEW 1.10.
//!
//! ponytail: lateral CA warp (10 §10.4), highlight recovery (10 §10.3), tone-selective 3D LUT
//! (10 §10.5) and saturation curves (10 §10.6, default 1.0) are skipped — coefficients UNKNOWN.
//! LSC uses bilinear instead of the 4-tap cubic kernel; the map is smooth.

use crate::img::{par_rows, Img};

pub fn srgb_oetf(x: f32) -> f32 {
    let x = x.clamp(0.0, 1.0);
    if x <= 0.0031308 { 12.92 * x } else { 1.055 * x.powf(1.0 / 2.4) - 0.055 }
}

pub fn srgb_eotf(y: f32) -> f32 {
    let y = y.clamp(0.0, 1.0);
    if y <= 0.04045 { y / 12.92 } else { ((y + 0.055) / 1.055).powf(2.4) }
}

/// Gray-world WB on a 64x48 grid of quad means, masking dark/clipped cells (10 §10.1 p.1).
/// Returns canonical gains [R, Gr, Gb, B] normalized to G = 1.
pub fn gray_world(planes: &[Img; 4]) -> [f32; 4] {
    let (gw, gh) = (64usize, 48usize);
    let (pw, ph) = (planes[0].w, planes[0].h);
    let mut sum = [0.0f64; 3];
    for gy in 0..gh {
        for gx in 0..gw {
            let (x0, x1) = (gx * pw / gw, ((gx + 1) * pw / gw).max(gx * pw / gw + 1));
            let (y0, y1) = (gy * ph / gh, ((gy + 1) * ph / gh).max(gy * ph / gh + 1));
            let mut m = [0.0f64; 4];
            let mut clipped = false;
            for y in y0..y1.min(ph) {
                for x in x0..x1.min(pw) {
                    for c in 0..4 {
                        let v = planes[c].d[y * pw + x];
                        clipped |= v >= 0.98;
                        m[c] += v as f64;
                    }
                }
            }
            let g = 0.5 * (m[1] + m[2]);
            let n = ((x1 - x0) * (y1 - y0)) as f64;
            if clipped || g / n < 0.01 {
                continue;
            }
            sum[0] += m[0];
            sum[1] += g;
            sum[2] += m[3];
        }
    }
    if sum[0] <= 0.0 || sum[2] <= 0.0 {
        return [1.0; 4];
    }
    [(sum[1] / sum[0]) as f32, 1.0, 1.0, (sum[1] / sum[2]) as f32]
}

/// Lens shading map from Camera2: `cols x rows` points, 4 gains per point in [R, G_even, G_odd, B].
pub struct Lsc<'a> {
    pub map: &'a [f32],
    pub cols: usize,
    pub rows: usize,
}

impl Lsc<'_> {
    /// Gain for (r, g, b) at normalized position (u, v) in [0,1]^2 of the active array.
    fn gains(&self, u: f32, v: f32) -> [f32; 3] {
        let fx = (u * (self.cols - 1) as f32).clamp(0.0, (self.cols - 1) as f32);
        let fy = (v * (self.rows - 1) as f32).clamp(0.0, (self.rows - 1) as f32);
        let (x0, y0) = (fx.floor() as usize, fy.floor() as usize);
        let (x1, y1) = ((x0 + 1).min(self.cols - 1), (y0 + 1).min(self.rows - 1));
        let (ax, ay) = (fx - x0 as f32, fy - y0 as f32);
        let g = |x: usize, y: usize, c: usize| self.map[4 * (y * self.cols + x) + c];
        let bil = |c: usize| {
            let a = g(x0, y0, c) * (1.0 - ax) + g(x1, y0, c) * ax;
            let b = g(x0, y1, c) * (1.0 - ax) + g(x1, y1, c) * ax;
            (a * (1.0 - ay) + b * ay).max(1.0)
        };
        [bil(0), 0.5 * (bil(1) + bil(2)), bil(3)]
    }
}

/// rgb' = CCM · (LSC ⊙ rgb), clamped at 0 (10 §10.3). `ccm` is row-major 3x3.
pub fn lsc_ccm(rgb: &mut [Img; 3], lsc: Option<&Lsc>, ccm: &[f32; 9]) {
    let (w, h) = (rgb[0].w, rgb[0].h);
    let [r, g, b] = rgb;
    let (gp, bp) = (crate::img::SyncPtr(g.d.as_mut_ptr()), crate::img::SyncPtr(b.d.as_mut_ptr()));
    par_rows(&mut r.d, w, |y, row| {
        for (x, rv) in row.iter_mut().enumerate() {
            let i = y * w + x;
            let (gv, bv) = unsafe { (&mut *gp.get().add(i), &mut *bp.get().add(i)) };
            let k = lsc.map_or([1.0; 3], |l| l.gains((x as f32 + 0.5) / w as f32, (y as f32 + 0.5) / h as f32));
            let v = [*rv * k[0], *gv * k[1], *bv * k[2]];
            let o = |row: usize| (ccm[3 * row] * v[0] + ccm[3 * row + 1] * v[1] + ccm[3 * row + 2] * v[2]).max(0.0);
            (*rv, *gv, *bv) = (o(0), o(1), o(2));
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn srgb_roundtrip_and_gray_world() {
        for i in 0..=20 {
            let x = i as f32 / 20.0;
            assert!((srgb_eotf(srgb_oetf(x)) - x).abs() < 1e-5);
        }
        // Greenish cast: R = 0.5 G, B = 0.25 G -> gains 2 and 4.
        let planes = [0.1f32, 0.2, 0.2, 0.05].map(|v| Img::from_fn(128, 96, move |_, _| v));
        let g = gray_world(&planes);
        assert!((g[0] - 2.0).abs() < 1e-3 && (g[3] - 4.0).abs() < 1e-3);
    }

    #[test]
    fn lsc_and_ccm_applied() {
        let mut rgb = [0.2f32, 0.2, 0.2].map(|v| Img::from_fn(8, 8, move |_, _| v));
        // 2x2 map, gain 2 everywhere for R only.
        let map = [2.0, 1.0, 1.0, 1.0].repeat(4);
        let lsc = Lsc { map: &map, cols: 2, rows: 2 };
        let swap_rb = [0.0, 0.0, 1.0, 0.0, 1.0, 0.0, 1.0, 0.0, 0.0];
        lsc_ccm(&mut rgb, Some(&lsc), &swap_rb);
        assert!((rgb[2].d[0] - 0.4).abs() < 1e-6 && (rgb[0].d[0] - 0.2).abs() < 1e-6);
    }
}
