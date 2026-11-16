// SPDX-FileCopyrightText: borz7zy
// SPDX-License-Identifier: MIT

//! 06-superres: CPU MFSR ("spatial bayer", [W19]) at 1x output: guide (06 §6.3), anisotropic
//! kernels from the green structure tensor, robustness with motion-prior boost and 5x5 soft
//! dilation (06 §6.4), unnormalized kernel accumulation (06 §6.5), coverage-based resolve
//! against a demosaic of the base (06 §6.6). Defaults p0..p5, m_rgb/m_g, β, τ from 06 §6.7.
//!
//! ponytail: unblocker u ≡ 0 (no codec/ISP smoothing on RAW), no sqrt-space guide, bilinear
//! instead of B-spline guide sampling, Ω taken at the base position. CPU only — move to a
//! compute shader if the ~1G kernel taps per 10-frame burst are too slow on device.

use crate::align::Field;
use crate::demosaic::demosaic;
use crate::img::{lerp, par_for, Img, SyncPtr};
use crate::moire::MoireMap;
use crate::raw_prep::{Frame, B, GB, GR, R};

// 06 §6.7 (FACT), in inverse-sigma ("precision") units.
const P0: f32 = 0.25;
const S_BASE: f32 = 1.0 / P0;
const S_BLURRED: f32 = 1.0 / (P0 * 4.0);
const D_TH: f32 = 0.001;
const D_TR: f32 = 0.007;
const S_STRETCHED: f32 = 1.0 / (P0 * 3.0);
const S_SHRINKED: f32 = 1.5 / P0;
const M_RGB: f32 = 0.07;
const M_G: f32 = 0.35;
const BETA_MOTION: f32 = 6.0;
const TAU_VAR: f32 = 25.0;
/// 0.2 RAW px of local flow variation = 0.1 quad px (06 §6.7, REVIEW).
const TAU_MOTION_QUAD: f32 = 0.1;
/// Resolve blend start/end in accumulated kernel weight (UNKNOWN, ASSUMED).
const BLEND_START: f32 = 0.5;
const BLEND_END: f32 = 2.0;

#[derive(Clone, Copy, Default)]
struct Guide {
    rgb: [f32; 3],
    /// Negative = green-only reference (06 §6.3).
    var: f32,
    green_var: f32,
}

struct Noise {
    s: [f32; 3],
    o: [f32; 3],
}

impl Noise {
    fn new(s: &[f32; 4], o: &[f32; 4], wb: &[f32; 4], gain: f32) -> Self {
        // WB'd units: Var(x*g) = g*S*(x*g) + g^2*O ; frame gain likewise.
        let sg = 0.5 * (s[GR] + s[GB]);
        let og = 0.5 * (o[GR] + o[GB]);
        let wg = 0.5 * (wb[GR] + wb[GB]);
        Noise {
            s: [wb[R] * s[R] * gain, wg * sg * gain, wb[B] * s[B] * gain],
            o: [wb[R] * wb[R] * o[R] * gain * gain, wg * wg * og * gain * gain, wb[B] * wb[B] * o[B] * gain * gain],
        }
    }
    fn var(&self, c: usize, l: f32) -> f32 {
        (self.s[c] * l.max(0.0) + self.o[c]).max(1e-12)
    }
}

fn guide(f: &Frame, wb: &[f32; 4], noise: &Noise) -> Vec<Guide> {
    let (pw, ph) = (f.pw, f.ph);
    let mut out = vec![Guide::default(); pw * ph];
    let ptr = SyncPtr(out.as_mut_ptr());
    par_for(ph, |y| {
        for x in 0..pw {
            let (mut avg, mut m0, mut m1) = ([0.0f32; 3], [0.0f32; 3], [0.0f32; 3]);
            let (mut g0, mut g1) = (0.0f32, 0.0f32);
            let mut center_g = 0.0;
            let mut center_raw_g = 0.0;
            for dy in -1..=1isize {
                for dx in -1..=1isize {
                    let (qx, qy) = (x as isize + dx, y as isize + dy);
                    let v = |c: usize| f.get(c, qx, qy) * wb[c];
                    let (gr, gb) = (v(GR), v(GB));
                    let rgb = [v(R), 0.5 * (gr + gb), v(B)];
                    let wgt = (if dx == 0 { 0.5 } else { 0.25 }) * (if dy == 0 { 0.5 } else { 0.25 });
                    for c in 0..3 {
                        avg[c] += wgt * rgb[c];
                        m0[c] += rgb[c] / 9.0;
                        m1[c] += rgb[c] * rgb[c] / 9.0;
                    }
                    g0 += (gr + gb) / 18.0;
                    g1 += (gr * gr + gb * gb) / 18.0;
                    if dx == 0 && dy == 0 {
                        center_g = rgb[1];
                        center_raw_g = 0.5 * (f.get(GR, qx, qy) + f.get(GB, qx, qy));
                    }
                }
            }
            let rgb_var: [f32; 3] = std::array::from_fn(|c| (m1[c] - m0[c] * m0[c]).max(0.0));
            let green_var = (g1 - g0 * g0).max(0.0);
            let l = 0.25 * avg[0] + 0.5 * avg[1] + 0.25 * avg[2];
            let sigma_g = noise.var(1, l);
            let mut g = if green_var > 3.0 * sigma_g {
                Guide { rgb: [avg[0], center_g, avg[2]], var: -rgb_var[1].max(green_var), green_var }
            } else {
                Guide { rgb: avg, var: (rgb_var[0] + rgb_var[1] + rgb_var[2]) / 3.0, green_var }
            };
            if center_raw_g >= 0.98 {
                g.rgb = [10000.0; 3]; // force rejection of clipped areas
            }
            unsafe { *ptr.get().add(y * pw + x) = g };
        }
    });
    out
}

fn sample_guide(g: &[Guide], pw: usize, ph: usize, x: f32, y: f32) -> Guide {
    let x = x.clamp(0.0, (pw - 1) as f32);
    let y = y.clamp(0.0, (ph - 1) as f32);
    let (x0, y0) = (x.floor() as usize, y.floor() as usize);
    let (x1, y1) = ((x0 + 1).min(pw - 1), (y0 + 1).min(ph - 1));
    let (ax, ay) = (x - x0 as f32, y - y0 as f32);
    let taps = [(x0, y0, (1.0 - ax) * (1.0 - ay)), (x1, y0, ax * (1.0 - ay)), (x0, y1, (1.0 - ax) * ay), (x1, y1, ax * ay)];
    let mut out = Guide::default();
    for (tx, ty, w) in taps {
        let s = g[ty * pw + tx];
        for c in 0..3 {
            out.rgb[c] += w * s.rgb[c];
        }
        out.var += w * s.var.abs();
        out.green_var += w * s.green_var;
    }
    out
}

/// Kernel precision matrix Ω = R·diag(k1², k2²)·Rᵀ, R = [e1 e2] (REVIEW 1.5). Returns (Ω00, Ω11, Ω01).
pub fn kernel_cov(sxx: f32, syy: f32, sxy: f32, noise_corr: f32, green_var: f32, sigma2_g: f32) -> [f32; 3] {
    let tr = 0.5 * (sxx + syy);
    let disc = (0.25 * (sxx - syy) * (sxx - syy) + sxy * sxy).sqrt();
    let (mut l1, l2) = (tr + disc, (tr - disc).max(0.0));
    let e1 = if sxy.abs() <= 1e-12 {
        if sxx >= syy { [1.0, 0.0] } else { [0.0, 1.0] }
    } else {
        let v = [sxy, l1 - sxx];
        let n = (v[0] * v[0] + v[1] * v[1]).sqrt();
        [v[0] / n, v[1] / n]
    };
    let e2 = [-e1[1], e1[0]];
    l1 = l1 * l1 / (l1 + noise_corr).max(1e-20);
    let (s1, s2) = (l1.max(0.0).sqrt(), l2.sqrt());
    let a = (s1 - s2) / (s1 + s2 + 1e-6);
    let d = s1;
    let sigma_g = (green_var * green_var / (green_var + sigma2_g).max(1e-20)).sqrt();
    let blur = (1.0 - (d.max(sigma_g) - D_TH) / D_TR).clamp(0.0, 1.0);
    let shrink = lerp(S_BASE, S_SHRINKED, a.min(5.0 * d));
    let k1 = lerp(shrink, S_BLURRED, blur);
    let k2 = lerp(lerp(S_BASE, S_STRETCHED, a), S_BLURRED, blur);
    let (q1, q2) = (k1 * k1, k2 * k2);
    [
        q1 * e1[0] * e1[0] + q2 * e2[0] * e2[0],
        q1 * e1[1] * e1[1] + q2 * e2[1] * e2[1],
        q1 * e1[0] * e1[1] + q2 * e2[0] * e2[1],
    ]
}

fn base_kernels(f: &Frame, wb: &[f32; 4], g0: &[Guide], noise: &Noise, moire: Option<&MoireMap>) -> Vec<[f32; 3]> {
    let (pw, ph) = (f.pw, f.ph);
    let wg = 0.5 * (wb[GR] + wb[GB]);
    let green = Img::from_fn(pw, ph, |x, y| 0.5 * (f.get(GR, x as isize, y as isize) + f.get(GB, x as isize, y as isize)) * wg);
    let mut out = vec![[0.0f32; 3]; pw * ph];
    let ptr = SyncPtr(out.as_mut_ptr());
    par_for(ph, |y| {
        for x in 0..pw {
            let (mut sxx, mut syy, mut sxy) = (0.0f32, 0.0f32, 0.0f32);
            for dy in -1..=1isize {
                for dx in -1..=1isize {
                    let (qx, qy) = (x as isize + dx, y as isize + dy);
                    // central difference over 2 quads = 4 RAW px -> per RAW pixel
                    let gx = (green.at(qx + 1, qy) - green.at(qx - 1, qy)) * 0.25;
                    let gy = (green.at(qx, qy + 1) - green.at(qx, qy - 1)) * 0.25;
                    sxx += gx * gx / 9.0;
                    syy += gy * gy / 9.0;
                    sxy += gx * gy / 9.0;
                }
            }
            let g = g0[y * pw + x];
            let l = 0.25 * g.rgb[0] + 0.5 * g.rgb[1] + 0.25 * g.rgb[2];
            let s2 = noise.var(1, l.min(1.0));
            // Var(gx) = 2 * Var(mean of 2 greens) / 16 = s2 / 16
            let mut om = kernel_cov(sxx, syy, sxy, s2 / 16.0, g.green_var, s2);
            // Moire zones: wide isotropic kernel, i.e. average many frames/phases (no SR).
            if let Some(m) = moire {
                let t = m.at_quad(x as f32, y as f32);
                let q = S_BLURRED * S_BLURRED;
                om = [lerp(om[0], q, t), lerp(om[1], q, t), lerp(om[2], 0.0, t)];
            }
            unsafe { *ptr.get().add(y * pw + x) = om };
        }
    });
    out
}

/// Robustness weight per base quad for frame f (06 §6.4) followed by the 5x5 soft dilation.
fn robustness(g0: &[Guide], gf: &[Guide], field: &Field, pw: usize, ph: usize, n0: &Noise, nf: &Noise) -> Img {
    let rej = Img::from_fn(pw, ph, |x, y| {
        let r = g0[y * pw + x];
        let flow = field.at_px(x as f32, y as f32);
        let mut cur = sample_guide(gf, pw, ph, x as f32 + flow[0], y as f32 + flow[1]);
        let green_only = r.var < 0.0;
        let l = if green_only { r.rgb[1] } else { (r.rgb[0] + r.rgb[1] + r.rgb[2]) / 3.0 };
        let k = if green_only { 0.211665 } else { 0.0898866 };
        let s_ref: [f32; 3] = std::array::from_fn(|c| k * n0.var(c, l));
        let s_cur: [f32; 3] = std::array::from_fn(|c| k * nf.var(c, l));
        let ref_var = k * r.var.abs();
        cur.var *= k;
        let pix_var = 2.0 * ref_var.min(cur.var);
        let motion_prior = field.variation_px(x as f32, y as f32) > TAU_MOTION_QUAD;
        let boost = if motion_prior && ref_var > TAU_VAR * (s_ref[0] + s_ref[1] + s_ref[2]) / 3.0 { BETA_MOTION } else { 1.0 };
        let d2: [f32; 3] = std::array::from_fn(|c| {
            let sn = s_ref[c] + s_cur[c];
            ((cur.rgb[c] - r.rgb[c]).powi(2) - sn).max(0.0) / pix_var.max(sn)
        });
        let dist = if green_only { M_G * d2[1] } else { M_RGB * (d2[0] + d2[1] + d2[2]) / 3.0 };
        1.0 - (-dist * boost).exp2()
    });
    Img::from_fn(pw, ph, |x, y| {
        let mut s = 0.0;
        for dy in -2..=2isize {
            for dx in -2..=2isize {
                s += rej.at(x as isize + dx, y as isize + dy);
            }
        }
        1.0 - ((s - 0.2) / 2.0).clamp(0.0, 1.0)
    })
}

/// 2^x for x <= 0, ~1e-4 relative error (kernel weights do not need libm accuracy).
#[inline(always)]
fn exp2_neg(x: f32) -> f32 {
    let x = x.max(-60.0);
    // floor without a libm call (x <= 0: truncation rounds up)
    let mut xi = x as i32;
    if (xi as f32) > x {
        xi -= 1;
    }
    let f = x - xi as f32; // [0, 1)
    let p = 1.0 + f * (0.695_623_2 + f * (0.226_091_3 + f * 0.078_024_5));
    f32::from_bits(((xi + 127) as u32) << 23) * p
}

/// MFSR merge. Returns full-res linear WB'd camera RGB and the mean accepted frame count.
pub fn superres(
    frames: &[Frame],
    fields: &[Field],
    wb: &[f32; 4],
    s: &[f32; 4],
    o: &[f32; 4],
    moire: Option<&MoireMap>,
) -> ([Img; 3], f32) {
    let base = &frames[0];
    let (pw, ph) = (base.pw, base.ph);
    let (w, h) = (2 * pw, 2 * ph);
    let n0 = Noise::new(s, o, wb, 1.0);
    let g0 = guide(base, wb, &n0);
    let omega = base_kernels(base, wb, &g0, &n0, moire);
    let weights: Vec<Img> = (1..frames.len())
        .map(|f| {
            let nf = Noise::new(s, o, wb, frames[f].gain);
            let gf = guide(&frames[f], wb, &nf);
            robustness(&g0, &gf, &fields[f - 1], pw, ph, &n0, &nf)
        })
        .collect();
    let m_eff = 1.0 + weights.iter().map(|w| w.mean()).sum::<f32>();

    let fallback = demosaic(&std::array::from_fn(|c| base.plane(c)), wb);
    let mut out: [Img; 3] = std::array::from_fn(|_| Img::new(w, h));
    let ptrs: [SyncPtr<f32>; 3] = std::array::from_fn(|c| SyncPtr(out[c].d.as_mut_ptr()));
    let nf = frames.len();
    // One task per quad row (2 output rows); flow and robustness are per quad.
    par_for(ph, |qy| {
        let flows: Vec<[f32; 2]> = (0..pw * (nf - 1))
            .map(|k| {
                let fl = fields[k / pw].at_px((k % pw) as f32, qy as f32);
                [2.0 * fl[0], 2.0 * fl[1]]
            })
            .collect();
        for v in 2 * qy..2 * qy + 2 {
            for u in 0..w {
                let qx = u / 2;
                let om = omega[qy * pw + qx];
                let (mut acc, mut wsum) = ([0.0f32; 3], [0.0f32; 3]);
                for (f, fr) in frames.iter().enumerate() {
                    let (flow, wf) = if f == 0 {
                        ([0.0, 0.0], 1.0)
                    } else {
                        (flows[(f - 1) * pw + qx], weights[f - 1].d[qy * pw + qx])
                    };
                    if wf <= 0.0 {
                        continue;
                    }
                    let pos = [u as f32 + flow[0], v as f32 + flow[1]];
                    let bp = [pos[0].round(), pos[1].round()];
                    let delta = [bp[0] - pos[0], bp[1] - pos[1]];
                    let (bx, by) = (bp[0] as isize, bp[1] as isize);
                    let inside = bx >= 1 && by >= 1 && bx + 1 < w as isize && by + 1 < h as isize;
                    for j in -1..=1isize {
                        for i in -1..=1isize {
                            let (uu, vv) = (bx + i, by + j);
                            let (val, c) = if inside {
                                let c = (uu as usize & 1) + 2 * (vv as usize & 1);
                                let raw = fr.data[(fr.oy + vv as usize) * fr.stride + fr.ox + uu as usize];
                                ((raw as f32 - fr.bl[c]) * fr.scale[c], c)
                            } else {
                                fr.get_raw(uu, vv)
                            };
                            let (dx, dy) = (delta[0] + i as f32, delta[1] + j as f32);
                            let q = om[0] * dx * dx + om[1] * dy * dy + 2.0 * om[2] * dx * dy;
                            let k = wf * (exp2_neg(-0.5 * q) + 5e-5);
                            let ch = [0, 1, 1, 2][c];
                            acc[ch] += k * val * wb[c];
                            wsum[ch] += k;
                        }
                    }
                }
                let i = v * w + u;
                for ch in 0..3 {
                    let rgb = acc[ch] / wsum[ch].max(1e-7);
                    let alpha = ((wsum[ch] - BLEND_START) / (BLEND_END - BLEND_START)).clamp(0.0, 1.0);
                    // SAFETY: each task writes only its own two rows.
                    unsafe { *ptrs[ch].get().add(i) = lerp(fallback[ch].d[i], rgb.clamp(0.0, 1.0), alpha) };
                }
            }
        }
    });
    (out, m_eff)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::raw_prep::synth_raw;

    #[test]
    fn fast_exp2() {
        for i in 0..400 {
            let x = -(i as f32) * 0.05;
            let (a, b) = (exp2_neg(x), x.exp2());
            assert!((a - b).abs() <= 2e-4 * b + 1e-30, "{x}: {a} vs {b}");
        }
    }

    #[test]
    fn kernel_orientation_follows_edges() {
        // Strong vertical edge: gradient along x -> narrow across x (Ω00 > Ω11).
        let v = kernel_cov(1e-2, 0.0, 0.0, 0.0, 1e-2, 1e-6);
        assert!(v[0] > v[1]);
        // Gradient along (1,-1): e1 = (1,-1)/√2 -> Ω01 < 0 with R·D·Rᵀ.
        let d = kernel_cov(0.5e-2, 0.5e-2, -0.5e-2, 0.0, 1e-2, 1e-6);
        assert!(d[2] < 0.0, "{d:?}");
        // Flat & noisy -> isotropic blurred kernel 1/(p0*p1) = 1.
        let f = kernel_cov(0.0, 0.0, 0.0, 0.0, 0.0, 1e-4);
        assert!((f[0] - 1.0).abs() < 1e-4 && (f[1] - 1.0).abs() < 1e-4 && f[2].abs() < 1e-6);
    }

    #[test]
    fn static_subpixel_burst_reconstructs_color() {
        let (w, h, n) = (64usize, 64usize, 6usize);
        let truth = |x: f32, y: f32| [0.3 + 0.2 * (x * 0.05).sin(), 0.4 + 0.1 * (y * 0.07).cos(), 0.25];
        // frame f is shifted by (sx, sy) RAW px: alt(p + s) = base(p)
        let shifts: Vec<[f32; 2]> = (0..n).map(|f| [0.5 * (f % 3) as f32, 0.5 * (f / 3) as f32]).collect();
        let mut raws: Vec<Vec<u16>> = shifts
            .iter()
            .map(|s| {
                synth_raw(w, h, 0.0, 65535.0, |x, y| {
                    let t = truth(x as f32 - s[0], y as f32 - s[1]);
                    [t[0], t[1], t[1], t[2]][(x & 1) + 2 * (y & 1)]
                })
            })
            .collect();
        let frames: Vec<Frame> = raws.iter_mut().map(|r| Frame::new(r, w, h, w, 0, [0.0; 4], 65535.0, 1.0)).collect();
        let fields: Vec<Field> = shifts[1..]
            .iter()
            .map(|s| Field { nx: 4, ny: 4, step: 8, tile: 16, v: vec![[s[0] / 2.0, s[1] / 2.0]; 16] })
            .collect();
        let (rgb, m_eff) = superres(&frames, &fields, &[1.0; 4], &[1e-5; 4], &[1e-7; 4], None);
        assert!(m_eff > n as f32 - 0.5, "m_eff {m_eff}");
        for y in 8..56 {
            for x in 8..56 {
                let t = truth(x as f32, y as f32);
                for c in 0..3 {
                    let e = (rgb[c].d[y * w + x] - t[c]).abs();
                    assert!(e < 0.03, "c{c} ({x},{y}) {e}");
                }
            }
        }
    }
}
