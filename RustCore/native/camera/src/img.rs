// SPDX-FileCopyrightText: borz7zy
// SPDX-License-Identifier: MIT

//! Minimal f32 image + parallel helpers shared by all stages.

use std::sync::atomic::{AtomicUsize, Ordering};

#[derive(Clone, Debug)]
pub struct Img {
    pub w: usize,
    pub h: usize,
    pub d: Vec<f32>,
}

impl Img {
    pub fn new(w: usize, h: usize) -> Self {
        Img { w, h, d: vec![0.0; w * h] }
    }

    pub fn from_fn(w: usize, h: usize, f: impl Fn(usize, usize) -> f32 + Sync) -> Self {
        let mut img = Img::new(w, h);
        par_rows(&mut img.d, w, |y, row| {
            for (x, v) in row.iter_mut().enumerate() {
                *v = f(x, y);
            }
        });
        img
    }

    /// Clamped access.
    #[inline]
    pub fn at(&self, x: isize, y: isize) -> f32 {
        let x = x.clamp(0, self.w as isize - 1) as usize;
        let y = y.clamp(0, self.h as isize - 1) as usize;
        self.d[y * self.w + x]
    }

    /// Mirrored access (reflect about the edge pixel); preserves Bayer parity.
    #[inline]
    pub fn at_mirror(&self, x: isize, y: isize) -> f32 {
        let m = |v: isize, n: isize| {
            let v = if v < 0 { -v } else { v };
            (if v >= n { 2 * (n - 1) - v } else { v }).clamp(0, n - 1) as usize
        };
        self.d[m(y, self.h as isize) * self.w + m(x, self.w as isize)]
    }

    /// Bilinear sample, pixel centers at integer coordinates, clamped.
    #[inline]
    pub fn sample(&self, x: f32, y: f32) -> f32 {
        let x = x.clamp(0.0, (self.w - 1) as f32);
        let y = y.clamp(0.0, (self.h - 1) as f32);
        let (x0, y0) = (x.floor() as isize, y.floor() as isize);
        let (fx, fy) = (x - x0 as f32, y - y0 as f32);
        let a = self.at(x0, y0) * (1.0 - fx) + self.at(x0 + 1, y0) * fx;
        let b = self.at(x0, y0 + 1) * (1.0 - fx) + self.at(x0 + 1, y0 + 1) * fx;
        a * (1.0 - fy) + b * fy
    }

    /// 2x2 box downsample.
    pub fn down2(&self) -> Img {
        let (w, h) = ((self.w / 2).max(1), (self.h / 2).max(1));
        Img::from_fn(w, h, |x, y| {
            let (x, y) = (2 * x as isize, 2 * y as isize);
            0.25 * (self.at(x, y) + self.at(x + 1, y) + self.at(x, y + 1) + self.at(x + 1, y + 1))
        })
    }

    /// Separable symmetric blur with odd-length kernel `k` (clamped borders).
    pub fn blur(&self, k: &[f32]) -> Img {
        let r = (k.len() / 2) as isize;
        let tmp = Img::from_fn(self.w, self.h, |x, y| {
            k.iter().enumerate().map(|(i, &kv)| kv * self.at(x as isize + i as isize - r, y as isize)).sum()
        });
        Img::from_fn(self.w, self.h, |x, y| {
            k.iter().enumerate().map(|(i, &kv)| kv * tmp.at(x as isize, y as isize + i as isize - r)).sum()
        })
    }

    /// Gaussian-pyramid step: 5-tap binomial blur then decimate by 2.
    /// Only decimated positions are filtered (horizontal at even x, vertical at even y).
    pub fn pyr_down(&self) -> Img {
        let (w2, h2) = ((self.w + 1) / 2, (self.h + 1) / 2);
        let k = BINOMIAL5;
        let tmp = Img::from_fn(w2, self.h, |x, y| {
            (0..5).map(|i| k[i] * self.at(2 * x as isize + i as isize - 2, y as isize)).sum()
        });
        Img::from_fn(w2, h2, |x, y| {
            (0..5).map(|i| k[i] * tmp.at(x as isize, 2 * y as isize + i as isize - 2)).sum()
        })
    }

    /// Bilinear resize to (w, h), sample-center aligned (per-axis tap tables, no per-pixel floor).
    pub fn resize(&self, w: usize, h: usize) -> Img {
        let taps = |n_out: usize, n_in: usize| -> Vec<(usize, usize, f32)> {
            let sc = n_in as f32 / n_out as f32;
            (0..n_out)
                .map(|i| {
                    let f = ((i as f32 + 0.5) * sc - 0.5).clamp(0.0, (n_in - 1) as f32);
                    let i0 = f as usize;
                    (i0, (i0 + 1).min(n_in - 1), f - i0 as f32)
                })
                .collect()
        };
        let (tx, ty) = (taps(w, self.w), taps(h, self.h));
        let mut out = Img::new(w, h);
        par_rows(&mut out.d, w, |y, row| {
            let (y0, y1, ay) = ty[y];
            let (r0, r1) = (&self.d[y0 * self.w..][..self.w], &self.d[y1 * self.w..][..self.w]);
            for (v, &(x0, x1, ax)) in row.iter_mut().zip(&tx) {
                let a = r0[x0] + (r0[x1] - r0[x0]) * ax;
                let b = r1[x0] + (r1[x1] - r1[x0]) * ax;
                *v = a + (b - a) * ay;
            }
        });
        out
    }

    /// Box mean over (2r+1)^2 window, clamped borders, O(1) per pixel.
    /// Both passes walk rows (vertical pass keeps per-column running sums) for cache locality.
    pub fn box_mean(&self, r: usize) -> Img {
        let n = (2 * r + 1) as f32;
        let ri = r as isize;
        let (w, h) = (self.w, self.h);
        let mut tmp = Img::new(w, h);
        par_rows(&mut tmp.d, w, |y, row| {
            let src = &self.d[y * w..(y + 1) * w];
            let get = |i: isize| src[i.clamp(0, w as isize - 1) as usize];
            let mut acc: f32 = (-ri..=ri).map(get).sum();
            for (x, v) in row.iter_mut().enumerate() {
                *v = acc / n;
                acc += get(x as isize + ri + 1) - get(x as isize - ri);
            }
        });
        let mut out = Img::new(w, h);
        const BAND: usize = 64;
        let ptr = SyncPtr(out.d.as_mut_ptr());
        par_for(w.div_ceil(BAND), |band| {
            let (x0, x1) = (band * BAND, ((band + 1) * BAND).min(w));
            let row = |y: isize| &tmp.d[y.clamp(0, h as isize - 1) as usize * w..][x0..x1];
            let mut acc = vec![0.0f32; x1 - x0];
            for y in -ri..=ri {
                acc.iter_mut().zip(row(y)).for_each(|(a, v)| *a += v);
            }
            for y in 0..h {
                let dst = unsafe { std::slice::from_raw_parts_mut(ptr.get().add(y * w + x0), x1 - x0) };
                let (add, sub) = (row(y as isize + ri + 1), row(y as isize - ri));
                for i in 0..x1 - x0 {
                    dst[i] = acc[i] / n;
                    acc[i] += add[i] - sub[i];
                }
            }
        });
        out
    }

    pub fn mean(&self) -> f32 {
        self.d.iter().map(|&v| v as f64).sum::<f64>() as f32 / self.d.len() as f32
    }
}

pub const BINOMIAL5: [f32; 5] = [1.0 / 16.0, 4.0 / 16.0, 6.0 / 16.0, 4.0 / 16.0, 1.0 / 16.0];
pub const BINOMIAL3: [f32; 3] = [0.25, 0.5, 0.25];

#[inline]
pub fn smoothstep(e0: f32, e1: f32, x: f32) -> f32 {
    let t = ((x - e0) / (e1 - e0)).clamp(0.0, 1.0);
    t * t * (3.0 - 2.0 * t)
}

#[inline]
pub fn lerp(a: f32, b: f32, t: f32) -> f32 {
    a + (b - a) * t
}

/// Piecewise-linear interpolation over sorted knots.
pub fn plf(knots: &[(f32, f32)], x: f32) -> f32 {
    if x <= knots[0].0 {
        return knots[0].1;
    }
    for w in knots.windows(2) {
        if x <= w[1].0 {
            return lerp(w[0].1, w[1].1, (x - w[0].0) / (w[1].0 - w[0].0));
        }
    }
    knots[knots.len() - 1].1
}

/// Raw pointer that may be shared between worker threads. Callers guarantee disjoint writes.
#[derive(Clone, Copy)]
pub struct SyncPtr<T>(pub *mut T);
unsafe impl<T> Send for SyncPtr<T> {}
unsafe impl<T> Sync for SyncPtr<T> {}
impl<T> SyncPtr<T> {
    #[inline]
    pub fn get(self) -> *mut T {
        self.0
    }
}

/// Run `f(i)` for i in 0..n on all cores (dynamic scheduling).
pub fn par_for(n: usize, f: impl Fn(usize) + Sync) {
    let threads = std::thread::available_parallelism().map_or(4, |v| v.get()).min(n.max(1));
    let next = AtomicUsize::new(0);
    std::thread::scope(|s| {
        for _ in 0..threads {
            s.spawn(|| loop {
                let i = next.fetch_add(1, Ordering::Relaxed);
                if i >= n {
                    break;
                }
                f(i);
            });
        }
    });
}

/// Run `f(y, row)` for every row of a w-wide buffer in parallel.
pub fn par_rows(d: &mut [f32], w: usize, f: impl Fn(usize, &mut [f32]) + Sync) {
    let h = d.len() / w.max(1);
    let ptr = SyncPtr(d.as_mut_ptr());
    par_for(h, |y| {
        let row = unsafe { std::slice::from_raw_parts_mut(ptr.get().add(y * w), w) };
        f(y, row);
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn box_mean_constant_and_plf() {
        let img = Img::from_fn(37, 11, |_, _| 3.0);
        assert!(img.box_mean(2).d.iter().all(|&v| (v - 3.0).abs() < 1e-5));
        assert_eq!(plf(&[(0.0, 0.0), (2.0, 4.0)], 1.0), 2.0);
        let ramp = Img::from_fn(8, 8, |x, _| x as f32);
        assert!((ramp.sample(2.5, 3.0) - 2.5).abs() < 1e-6);
    }
}
