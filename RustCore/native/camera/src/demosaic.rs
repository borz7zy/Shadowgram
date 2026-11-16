// SPDX-FileCopyrightText: borz7zy
// SPDX-License-Identifier: MIT

//! 07-demosaic: linearize (planes are already BL-subtracted/normalized), apply WB gains,
//! clamp [0,1] (clipped highlights -> white), PPG demosaic (07 §7.2), linear YUV (07 §7.3).
//!
//! ponytail: "demosaic sharpness" knob (07 §7.2 step 4) not implemented — UNKNOWN semantics;
//! the finish sharpener covers it.

use crate::img::Img;

#[inline]
fn hue_transit(l1: f32, l2: f32, l3: f32, v1: f32, v3: f32) -> f32 {
    if (l1 < l2 && l2 < l3) || (l1 > l2 && l2 > l3) {
        v1 + (v3 - v1) * (l2 - l1) / (l3 - l1)
    } else {
        (v1 + v3) * 0.5 + (2.0 * l2 - l1 - l3) * 0.25
    }
}

/// Canonical RGGB planes (half-res) + canonical WB gains -> full-res linear camera RGB.
pub fn demosaic(planes: &[Img; 4], wb: &[f32; 4]) -> [Img; 3] {
    let (pw, ph) = (planes[0].w, planes[0].h);
    let (w, h) = (2 * pw, 2 * ph);
    let m = Img::from_fn(w, h, |x, y| {
        let c = (x & 1) + 2 * (y & 1);
        (planes[c].d[(y / 2) * pw + x / 2] * wb[c]).clamp(0.0, 1.0)
    });
    let at = |img: &Img, x: usize, y: usize, dx: isize, dy: isize| img.at_mirror(x as isize + dx, y as isize + dy);

    // Step 1: green at R/B (4 directions, gradient + color laplacian).
    let g = Img::from_fn(w, h, |x, y| {
        let c = at(&m, x, y, 0, 0);
        if (x + y) & 1 == 1 {
            return c;
        }
        let mm = |dx, dy| at(&m, x, y, dx, dy);
        let gv = (mm(0, -1) - mm(0, 1)).abs();
        let gh = (mm(-1, 0) - mm(1, 0)).abs();
        let dirs = [
            (2.0 * (mm(0, -2) - c).abs() + gv, (0isize, -1isize)),
            (2.0 * (mm(2, 0) - c).abs() + gh, (1, 0)),
            (2.0 * (mm(-2, 0) - c).abs() + gh, (-1, 0)),
            (2.0 * (mm(0, 2) - c).abs() + gv, (0, 1)),
        ];
        let (_, (dx, dy)) = dirs.iter().copied().min_by(|a, b| a.0.total_cmp(&b.0)).unwrap();
        ((3.0 * mm(dx, dy) + mm(-dx, -dy) + c - mm(2 * dx, 2 * dy)) * 0.25).clamp(0.0, 1.0)
    });

    // Steps 2-3: R and B everywhere via hue transit.
    let chan = |target: usize| {
        Img::from_fn(w, h, |x, y| {
            let c = (x & 1) + 2 * (y & 1);
            let mm = |dx, dy| at(&m, x, y, dx, dy);
            let gg = |dx, dy| at(&g, x, y, dx, dy);
            if c == target {
                mm(0, 0)
            } else if c == 1 || c == 2 {
                // On Gr the horizontal neighbors are R; on Gb they are B.
                if (c == 1) == (target == 0) {
                    hue_transit(gg(-1, 0), gg(0, 0), gg(1, 0), mm(-1, 0), mm(1, 0))
                } else {
                    hue_transit(gg(0, -1), gg(0, 0), gg(0, 1), mm(0, -1), mm(0, 1))
                }
            } else {
                let ne = (mm(1, -1) - mm(-1, 1)).abs() + (gg(1, -1) - gg(0, 0)).abs() + (gg(0, 0) - gg(-1, 1)).abs();
                let nw = (mm(-1, -1) - mm(1, 1)).abs() + (gg(-1, -1) - gg(0, 0)).abs() + (gg(0, 0) - gg(1, 1)).abs();
                if ne < nw {
                    hue_transit(gg(1, -1), gg(0, 0), gg(-1, 1), mm(1, -1), mm(-1, 1))
                } else {
                    hue_transit(gg(-1, -1), gg(0, 0), gg(1, 1), mm(-1, -1), mm(1, 1))
                }
            }
            .clamp(0.0, 1.0)
        })
    };
    let r = chan(0);
    let b = chan(3);
    [r, g, b]
}

/// 07 §7.3: Y = R/4 + G/2 + B/4.
pub fn luma(rgb: &[Img; 3]) -> Img {
    Img::from_fn(rgb[0].w, rgb[0].h, |x, y| {
        let i = y * rgb[0].w + x;
        0.25 * rgb[0].d[i] + 0.5 * rgb[1].d[i] + 0.25 * rgb[2].d[i]
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn planes_from(f: impl Fn(usize, usize) -> [f32; 3] + Sync) -> [Img; 4] {
        std::array::from_fn(|c| {
            Img::from_fn(32, 32, |x, y| {
                let (u, v) = (2 * x + (c & 1), 2 * y + (c >> 1));
                let rgb = f(u, v);
                [rgb[0], rgb[1], rgb[1], rgb[2]][c]
            })
        })
    }

    #[test]
    fn flat_and_smooth_color() {
        let flat = demosaic(&planes_from(|_, _| [0.2, 0.4, 0.3]), &[1.0; 4]);
        assert!(flat[0].d.iter().all(|&v| (v - 0.2).abs() < 1e-5));
        assert!(flat[2].d.iter().all(|&v| (v - 0.3).abs() < 1e-5));
        let f = |u: usize, v: usize| {
            let t = (u as f32 + v as f32) / 128.0;
            [0.2 + 0.5 * t, 0.3 + 0.3 * t, 0.6 - 0.4 * t]
        };
        let out = demosaic(&planes_from(f), &[1.0; 4]);
        for y in 4..60 {
            for x in 4..60 {
                let t = f(x, y);
                for c in 0..3 {
                    let e = (out[c].d[y * 64 + x] - t[c]).abs();
                    assert!(e < 0.01, "c{c} ({x},{y}) err {e}");
                }
            }
        }
        // WB gains are applied and clamp to 1 turns clipped pixels white.
        let wb = demosaic(&planes_from(|_, _| [1.0, 1.0, 1.0]), &[2.0, 1.0, 1.0, 1.8]);
        assert!(wb.iter().all(|p| p.d.iter().all(|&v| (v - 1.0).abs() < 1e-6)));
    }
}
