// SPDX-FileCopyrightText: borz7zy
// SPDX-License-Identifier: MIT

//! 04-align: hierarchical tile alignment on the half-resolution gray image.
//! L2/L1 search on coarse levels, 3-candidate upsampling between levels ([H16]),
//! block Lucas-Kanade on the finest level, 3x3 median of the field.
//!
//! Levels (ASSUMED, [H16] / REVIEW 1.4): factors [1, 2, 4, 4] -> 1, 1/2, 1/8, 1/32 of gray.
//!
//! ponytail: no external (gyro/global) candidate (04 §4.5) — the 3 parent candidates suffice
//! for handheld bursts; add one if fast pans fail to align.

use crate::img::{par_for, Img, SyncPtr};

/// Tile displacement field. Tile (tx, ty) covers [tx*step, tx*step + tile) in its level.
/// Convention: alt(p + v) ~ base(p).
#[derive(Clone, Debug)]
pub struct Field {
    pub nx: usize,
    pub ny: usize,
    pub step: usize,
    pub tile: usize,
    pub v: Vec<[f32; 2]>,
}

impl Field {
    fn new(w: usize, h: usize, tile: usize) -> Self {
        let step = tile / 2;
        let (nx, ny) = (w.div_ceil(step).max(1), h.div_ceil(step).max(1));
        Field { nx, ny, step, tile, v: vec![[0.0; 2]; nx * ny] }
    }

    #[inline]
    fn get(&self, tx: isize, ty: isize) -> [f32; 2] {
        let tx = tx.clamp(0, self.nx as isize - 1) as usize;
        let ty = ty.clamp(0, self.ny as isize - 1) as usize;
        self.v[ty * self.nx + tx]
    }

    /// Bilinear interpolation of tile-center displacements at pixel (x, y) of this level.
    pub fn at_px(&self, x: f32, y: f32) -> [f32; 2] {
        let half = self.tile as f32 / 2.0;
        let fx = ((x - half) / self.step as f32).clamp(0.0, (self.nx - 1) as f32);
        let fy = ((y - half) / self.step as f32).clamp(0.0, (self.ny - 1) as f32);
        let (x0, y0) = (fx.floor() as isize, fy.floor() as isize);
        let (ax, ay) = (fx - x0 as f32, fy - y0 as f32);
        let mut out = [0.0; 2];
        for k in 0..2 {
            let a = self.get(x0, y0)[k] * (1.0 - ax) + self.get(x0 + 1, y0)[k] * ax;
            let b = self.get(x0, y0 + 1)[k] * (1.0 - ax) + self.get(x0 + 1, y0 + 1)[k] * ax;
            out[k] = a * (1.0 - ay) + b * ay;
        }
        out
    }

    /// Local flow variation (max - min of |v| in the 3x3 tile neighborhood) at pixel (x, y).
    pub fn variation_px(&self, x: f32, y: f32) -> f32 {
        let half = self.tile as f32 / 2.0;
        let tx = ((x - half) / self.step as f32).round() as isize;
        let ty = ((y - half) / self.step as f32).round() as isize;
        let (mut lo, mut hi) = (f32::MAX, f32::MIN);
        for dy in -1..=1 {
            for dx in -1..=1 {
                let v = self.get(tx + dx, ty + dy);
                let m = (v[0] * v[0] + v[1] * v[1]).sqrt();
                lo = lo.min(m);
                hi = hi.max(m);
            }
        }
        hi - lo
    }
}

struct Level {
    tile: usize,
    radius: isize,
    l2: bool,
    /// Downsample factor from the next finer level.
    factor: usize,
}

const LEVELS: [Level; 4] = [
    Level { tile: 16, radius: 1, l2: false, factor: 1 },
    // ±0.5 px at the parent (x4) is ±2 px here: radius 2 instead of [H16]'s 4 (ASSUMED).
    Level { tile: 16, radius: 2, l2: false, factor: 2 },
    Level { tile: 16, radius: 4, l2: false, factor: 4 },
    Level { tile: 8, radius: 4, l2: true, factor: 4 },
];
/// 04 §4.7: 2-3 LK iterations on the finest level.
const LK_ITERS: usize = 2;

pub fn pyramid(gray: Img) -> Vec<Img> {
    let mut p = vec![gray];
    for l in &LEVELS[1..] {
        let mut img = p.last().unwrap().pyr_down();
        if l.factor == 4 {
            img = img.pyr_down();
        }
        p.push(img);
    }
    p
}

/// Sum of |b-a| (or squared) over 8 lanes so LLVM vectorizes the float reduction.
#[inline]
fn row_err(b: &[f32], a: &[f32], l2: bool) -> f32 {
    let mut acc = [0.0f32; 8];
    let (bc, ac) = (b.chunks_exact(8), a.chunks_exact(8));
    let (br, ar) = (bc.remainder(), ac.remainder());
    for (b8, a8) in bc.zip(ac) {
        for k in 0..8 {
            let d = b8[k] - a8[k];
            acc[k] += if l2 { d * d } else { d.abs() };
        }
    }
    let mut e: f32 = acc.iter().sum();
    for (b, a) in br.iter().zip(ar) {
        let d = b - a;
        e += if l2 { d * d } else { d.abs() };
    }
    e
}

/// Tile error with early exit once `limit` is exceeded.
fn tile_err(base: &Img, alt: &Img, x0: usize, y0: usize, tile: usize, d: [isize; 2], l2: bool, limit: f32) -> f32 {
    let (ax, ay) = (x0 as isize + d[0], y0 as isize + d[1]);
    let inside = x0 + tile <= base.w && y0 + tile <= base.h && ax >= 0 && ay >= 0
        && ax as usize + tile <= alt.w && ay as usize + tile <= alt.h;
    let mut e = 0.0;
    if inside {
        for j in 0..tile {
            let bo = (y0 + j) * base.w + x0;
            let ao = (ay as usize + j) * alt.w + ax as usize;
            e += row_err(&base.d[bo..bo + tile], &alt.d[ao..ao + tile], l2);
            if e > limit {
                return e;
            }
        }
        return e;
    }
    for j in 0..tile {
        let y = (y0 + j) as isize;
        for i in 0..tile {
            let x = (x0 + i) as isize;
            let diff = base.at(x, y) - alt.at(x + d[0], y + d[1]);
            e += if l2 { diff * diff } else { diff.abs() };
        }
        if e > limit {
            return e;
        }
    }
    e
}

fn align_level(base: &Img, alt: &Img, lv: &Level, coarse: Option<(&Field, f32)>) -> Field {
    let mut field = Field::new(base.w, base.h, lv.tile);
    let (nx, step, tile) = (field.nx, field.step, field.tile);
    let ptr = SyncPtr(field.v.as_mut_ptr());
    par_for(field.nx * field.ny, |t| {
        let (tx, ty) = (t % nx, t / nx);
        let (x0, y0) = (tx * step, ty * step);
        // Initial candidates from the parent level (3 per [H16]).
        let mut init = [0isize; 2];
        if let Some((parent, k)) = coarse {
            let half_p = parent.tile as f32 / 2.0;
            let cx = (x0 + tile / 2) as f32 / k;
            let cy = (y0 + tile / 2) as f32 / k;
            let fx = (cx - half_p) / parent.step as f32;
            let fy = (cy - half_p) / parent.step as f32;
            let (px, py) = (fx.round() as isize, fy.round() as isize);
            let sx = if fx > px as f32 { 1 } else { -1 };
            let sy = if fy > py as f32 { 1 } else { -1 };
            let mut best = f32::MAX;
            for (cx, cy) in [(px, py), (px + sx, py), (px, py + sy)] {
                let v = parent.get(cx, cy);
                let c = [(v[0] * k).round() as isize, (v[1] * k).round() as isize];
                let e = tile_err(base, alt, x0, y0, tile, c, false, best);
                if e < best {
                    best = e;
                    init = c;
                }
            }
        }
        let mut best = (f32::MAX, init);
        for dy in -lv.radius..=lv.radius {
            for dx in -lv.radius..=lv.radius {
                let d = [init[0] + dx, init[1] + dy];
                let e = tile_err(base, alt, x0, y0, tile, d, lv.l2, best.0);
                if e < best.0 {
                    best = (e, d);
                }
            }
        }
        unsafe { *ptr.get().add(t) = [best.1[0] as f32, best.1[1] as f32] };
    });
    field
}

/// Base pyramid + level-0 gradients, shared by every alt frame.
pub struct Base {
    pyr: Vec<Img>,
    ix: Img,
    iy: Img,
}

impl Base {
    pub fn new(gray: Img) -> Self {
        let pyr = pyramid(gray);
        let b = &pyr[0];
        let ix = Img::from_fn(b.w, b.h, |x, y| 0.5 * (b.at(x as isize + 1, y as isize) - b.at(x as isize - 1, y as isize)));
        let iy = Img::from_fn(b.w, b.h, |x, y| 0.5 * (b.at(x as isize, y as isize + 1) - b.at(x as isize, y as isize - 1)));
        Base { pyr, ix, iy }
    }
}

/// Bilinear sample of a tile row: out[i] = alt(x0 + i + dx, y + dy).
#[inline]
fn sample_row(alt: &Img, x0: usize, y: usize, d: [f32; 2], out: &mut [f32]) {
    let (fx, fy) = (x0 as f32 + d[0], y as f32 + d[1]);
    let (ix, iy) = (fx.floor(), fy.floor());
    let (ax, ay) = (fx - ix, fy - iy);
    let (ix, iy) = (ix as isize, iy as isize);
    let n = out.len();
    if ix >= 0 && iy >= 0 && ix as usize + n < alt.w && (iy as usize) + 1 < alt.h {
        let r0 = &alt.d[iy as usize * alt.w + ix as usize..][..n + 1];
        let r1 = &alt.d[(iy as usize + 1) * alt.w + ix as usize..][..n + 1];
        for i in 0..n {
            let a = r0[i] + (r0[i + 1] - r0[i]) * ax;
            let b = r1[i] + (r1[i + 1] - r1[i]) * ax;
            out[i] = a + (b - a) * ay;
        }
    } else {
        for (i, o) in out.iter_mut().enumerate() {
            *o = alt.sample(fx + i as f32, fy);
        }
    }
}

/// Block Lucas-Kanade refinement (04 §4.4): Δ = -A^-1 b with base gradients, r = alt(p+d) - base(p).
fn lucas_kanade(base: &Base, alt: &Img, field: &mut Field) {
    let (nx, step, tile) = (field.nx, field.step, field.tile);
    let b0 = &base.pyr[0];
    let ptr = SyncPtr(field.v.as_mut_ptr());
    par_for(field.nx * field.ny, |t| {
        let (x0, y0) = ((t % nx) * step, (t / nx) * step);
        if x0 + tile > b0.w || y0 + tile > b0.h {
            return; // border tiles keep the integer result
        }
        let d0 = unsafe { *ptr.get().add(t) };
        let (mut a00, mut a01, mut a11) = (0.0f32, 0.0f32, 0.0f32);
        for j in 0..tile {
            let o = (y0 + j) * b0.w + x0;
            for (gx, gy) in base.ix.d[o..o + tile].iter().zip(&base.iy.d[o..o + tile]) {
                a00 += gx * gx;
                a01 += gx * gy;
                a11 += gy * gy;
            }
        }
        let det = a00 * a11 - a01 * a01;
        if det <= 1e-12 + 1e-6 * (a00 + a11) * (a00 + a11) {
            return; // textureless / aperture problem: keep integer result
        }
        let mut row = [0.0f32; 32];
        let row = &mut row[..tile];
        let mut d = d0;
        let mut e0 = 0.0;
        for it in 0..=LK_ITERS {
            let (mut v0, mut v1, mut e) = (0.0f32, 0.0f32, 0.0f32);
            for j in 0..tile {
                sample_row(alt, x0, y0 + j, d, row);
                let o = (y0 + j) * b0.w + x0;
                for i in 0..tile {
                    let r = row[i] - b0.d[o + i];
                    e += r.abs();
                    v0 += base.ix.d[o + i] * r;
                    v1 += base.iy.d[o + i] * r;
                }
            }
            if it == 0 {
                e0 = e;
            }
            if it == LK_ITERS {
                // Keep the refinement only if it lowered the L1 error.
                if e < e0 {
                    unsafe { *ptr.get().add(t) = d };
                }
                break;
            }
            let dx = -(a11 * v0 - a01 * v1) / det;
            let dy = -(a00 * v1 - a01 * v0) / det;
            d[0] += dx.clamp(-1.0, 1.0);
            d[1] += dy.clamp(-1.0, 1.0);
        }
    });
}

fn median3x3(field: &Field) -> Field {
    let mut out = field.clone();
    for ty in 0..field.ny as isize {
        for tx in 0..field.nx as isize {
            for k in 0..2 {
                let mut n = [0.0f32; 9];
                for (i, (dx, dy)) in (-1..=1).flat_map(|dy| (-1..=1).map(move |dx| (dx, dy))).enumerate() {
                    n[i] = field.get(tx + dx, ty + dy)[k];
                }
                n.sort_unstable_by(f32::total_cmp);
                out.v[ty as usize * field.nx + tx as usize][k] = n[4];
            }
        }
    }
    out
}

/// Align `alt` (gray pyramid from [`pyramid`]) to `base`. Returns the level-0 field
/// (tile 16, step 8, displacement in gray = plane pixels; ×2 for RAW pixels).
pub fn align(base: &Base, alt: &[Img]) -> Field {
    let mut field: Option<Field> = None;
    for l in (0..LEVELS.len()).rev() {
        let parent = field.as_ref().map(|f| (f, LEVELS[l + 1].factor as f32));
        let mut f = align_level(&base.pyr[l], &alt[l], &LEVELS[l], parent);
        if l == 0 {
            lucas_kanade(base, &alt[0], &mut f);
        }
        field = Some(f);
    }
    median3x3(&field.unwrap())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn texture(x: f32, y: f32) -> f32 {
        0.4 + 0.15 * (x * 0.21).sin() * (y * 0.17).cos() + 0.1 * ((x + 2.0 * y) * 0.05).sin()
            + 0.05 * ((x * 0.9).sin() + (y * 1.1).cos())
    }

    #[test]
    fn recovers_global_shift() {
        let (w, h) = (320, 256);
        let s = [13.4f32, -9.6f32];
        let base = Img::from_fn(w, h, |x, y| texture(x as f32, y as f32));
        // alt(p + s) = base(p)
        let alt = Img::from_fn(w, h, |x, y| texture(x as f32 - s[0], y as f32 - s[1]));
        let f = align(&Base::new(base), &pyramid(alt));
        let c = f.at_px(160.0, 128.0);
        assert!((c[0] - s[0]).abs() < 0.25 && (c[1] - s[1]).abs() < 0.25, "{c:?}");
        let inner: Vec<_> = (2..f.ny - 3).flat_map(|ty| (2..f.nx - 3).map(move |tx| (tx, ty))).collect();
        let bad = inner.iter().filter(|&&(tx, ty)| {
            let v = f.v[ty * f.nx + tx];
            (v[0] - s[0]).abs() > 0.5 || (v[1] - s[1]).abs() > 0.5
        }).count();
        assert!(bad * 20 < inner.len(), "bad tiles {bad}/{}", inner.len());
    }
}
