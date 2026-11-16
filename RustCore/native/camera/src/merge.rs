// SPDX-FileCopyrightText: borz7zy
// SPDX-License-Identifier: MIT

//! 05-merge: temporal + spatial Wiener filter in the DFT domain on 16x16 tiles per Bayer plane,
//! step 8, half-shifted Hann window (05 §5.2, FACT), overlap-add without renormalization.
//! Output: 4 f32 planes (normalized, no WB, negatives kept — REVIEW 1.8), merged noise model,
//! per-tile noise variance multiplier (REVIEW 1.9: f32 instead of Q8).

use crate::align::Field;
use crate::img::{lerp, par_for, smoothstep, Img, SyncPtr};
use crate::raw_prep::Frame;
use std::sync::OnceLock;

pub const T: usize = 16;
pub const STEP: usize = 8;

/// Tuning (05 §5.3 recommended values, ASSUMED — tune on device).
#[derive(Clone, Copy)]
pub struct WienerParams {
    pub lambda_t: f32,
    pub lambda_t_min: f32,
    pub lambda_s: f32,
    pub kappa: f32,
    pub theta_lo: f32,
    pub theta_hi: f32,
}

pub const DEFAULT_PARAMS: WienerParams = WienerParams {
    lambda_t: 8.0,
    lambda_t_min: 2.0,
    lambda_s: 1.0,
    kappa: 1.0,
    theta_lo: 1.5,
    theta_hi: 4.0,
};

#[derive(Clone, Copy, Default)]
struct C {
    re: f32,
    im: f32,
}

/// W16^m = exp(-2πi m/16), m = 0..9 (radix-4 twiddles; FACT: cos(kπ/8) constants, 05 §5.3).
const W16: [C; 10] = {
    const C8: f32 = 0.923_879_5; // cos(π/8)
    const S8: f32 = 0.382_683_43; // sin(π/8)
    const R2: f32 = std::f32::consts::FRAC_1_SQRT_2;
    [
        C { re: 1.0, im: 0.0 },
        C { re: C8, im: -S8 },
        C { re: R2, im: -R2 },
        C { re: S8, im: -C8 },
        C { re: 0.0, im: -1.0 },
        C { re: -S8, im: -C8 },
        C { re: -R2, im: -R2 },
        C { re: -C8, im: -S8 },
        C { re: -1.0, im: 0.0 },
        C { re: -C8, im: S8 },
    ]
};

#[inline(always)]
fn cmul(a: C, b: C) -> C {
    C { re: a.re * b.re - a.im * b.im, im: a.re * b.im + a.im * b.re }
}

#[inline(always)]
fn dft4(a0: C, a1: C, a2: C, a3: C) -> [C; 4] {
    let t0 = C { re: a0.re + a2.re, im: a0.im + a2.im };
    let t1 = C { re: a0.re - a2.re, im: a0.im - a2.im };
    let t2 = C { re: a1.re + a3.re, im: a1.im + a3.im };
    let t3 = C { re: a1.re - a3.re, im: a1.im - a3.im };
    [
        C { re: t0.re + t2.re, im: t0.im + t2.im },
        C { re: t1.re + t3.im, im: t1.im - t3.re }, // t1 - i*t3
        C { re: t0.re - t2.re, im: t0.im - t2.im },
        C { re: t1.re - t3.im, im: t1.im + t3.re }, // t1 + i*t3
    ]
}

pub fn window() -> &'static [f32; T] {
    static W: OnceLock<[f32; T]> = OnceLock::new();
    W.get_or_init(|| {
        let mut w = [0.0; T];
        for (n, v) in w.iter_mut().enumerate() {
            *v = (std::f32::consts::PI * (n as f32 + 0.5) / 16.0).sin().powi(2);
        }
        w
    })
}

/// In-place 16-point forward DFT, radix-4 (2 stages of 4), stride `stride`.
#[inline]
fn fft16(a: &mut [C], stride: usize) {
    let mut y = [[C::default(); 4]; 4];
    for n2 in 0..4 {
        let r = dft4(a[n2 * stride], a[(4 + n2) * stride], a[(8 + n2) * stride], a[(12 + n2) * stride]);
        for k1 in 0..4 {
            y[n2][k1] = cmul(r[k1], W16[n2 * k1]);
        }
    }
    for k1 in 0..4 {
        let x = dft4(y[0][k1], y[1][k1], y[2][k1], y[3][k1]);
        for k2 in 0..4 {
            a[(k1 + 4 * k2) * stride] = x[k2];
        }
    }
}

/// 2D DFT; the inverse (without 1/N) is conj(FFT(conj(x))).
fn fft2d(t: &mut [C; T * T], inv: bool) {
    if inv {
        t.iter_mut().for_each(|c| c.im = -c.im);
    }
    for r in 0..T {
        fft16(&mut t[r * T..], 1);
    }
    for c in 0..T {
        fft16(&mut t[c..], T);
    }
    if inv {
        t.iter_mut().for_each(|c| c.im = -c.im);
    }
}

/// Bins (u, v) with u <= 8: the other half of a real signal's spectrum is the conjugate mirror.
/// Weight = how many bins of the full spectrum each one stands for (1 on u = 0 / 8, else 2).
fn half_bins() -> &'static [(usize, usize, f32); 144] {
    static H: OnceLock<[(usize, usize, f32); 144]> = OnceLock::new();
    H.get_or_init(|| {
        std::array::from_fn(|i| {
            let (u, v) = (i % 9, i / 9);
            let k = v * T + u;
            let m = ((T - v) % T) * T + (T - u) % T;
            (k, m, if u == 0 || u == 8 { 1.0 } else { 2.0 })
        })
    })
}

/// Separate the half spectra of two real signals packed as a + i*b.
fn split(x: &[C; T * T]) -> [[C; T * T]; 2] {
    let mut a = [C::default(); T * T];
    let mut b = [C::default(); T * T];
    for &(k, m, _) in half_bins() {
        let (p, q) = (x[k], x[m]);
        a[k] = C { re: 0.5 * (p.re + q.re), im: 0.5 * (p.im - q.im) };
        b[k] = C { re: 0.5 * (p.im + q.im), im: -0.5 * (p.re - q.re) };
    }
    [a, b]
}

fn shaping() -> &'static [f32; T * T] {
    static S: OnceLock<[f32; T * T]> = OnceLock::new();
    S.get_or_init(|| {
        std::array::from_fn(|k| {
            let (fu, fv) = ((k % T).min(T - k % T) as f32, (k / T).min(T - k / T) as f32);
            1.0 + (fu * fu + fv * fv).sqrt() / (8.0 * std::f32::consts::SQRT_2)
        })
    })
}

pub struct Merged {
    pub planes: [Img; 4],
    /// Merged noise model (00 §3, 05 §5.4): S / M_eff, O / M_eff.
    pub s: [f32; 4],
    pub o: [f32; 4],
    /// Noise variance multiplier per merge tile (center of tile (tx,ty) at plane 8*tx, 8*ty),
    /// clamp(N / N_eff, 1, N) (05 §5.5).
    pub strength: Img,
}

/// Wiener merge. `frames[0]` is the base, `fields[f-1]` aligns frame f (plane pixels).
pub fn merge(frames: &[Frame], fields: &[Field], s: &[f32; 4], o: &[f32; 4], p: &WienerParams) -> Merged {
    let base = &frames[0];
    let (pw, ph) = (base.pw, base.ph);
    let n = frames.len();
    // Tiles start at -8 so every pixel is covered by 2 tiles per axis (sum of windows = 1).
    let (ntx, nty) = (pw.div_ceil(STEP) + 1, ph.div_ceil(STEP) + 1);
    let (padw, padh) = (STEP * ntx + STEP, STEP * nty + STEP);
    let mut out: Vec<Vec<f32>> = (0..4).map(|_| vec![0.0f32; padw * padh]).collect();
    let mut strength = Img::new(ntx, nty);
    let mut neff_tiles = vec![1.0f32; ntx * nty];
    let w = window();
    let win_sq_sum: f32 = w.iter().map(|v| v * v).sum::<f32>().powi(2); // = 36

    let out_ptrs: Vec<SyncPtr<f32>> = out.iter_mut().map(|o| SyncPtr(o.as_mut_ptr())).collect();
    let st_ptr = SyncPtr(strength.d.as_mut_ptr());
    let ne_ptr = SyncPtr(neff_tiles.as_mut_ptr());

    let tile_row = |ty: usize| {
        for tx in 0..ntx {
            let (x0, y0) = (STEP as isize * tx as isize - 8, STEP as isize * ty as isize - 8);
            let center = [(x0 + 8) as f32, (y0 + 8) as f32];
            // Integer per-frame offsets (04 §4.6: whole Bayer quads for Wiener).
            let offs: Vec<[isize; 2]> = (0..n)
                .map(|f| {
                    if f == 0 {
                        return [0, 0];
                    }
                    let v = fields[f - 1].at_px(center[0], center[1]);
                    [v[0].round() as isize, v[1].round() as isize]
                })
                .collect();
            let load = |f: usize, c: usize, buf: &mut [f32; T * T]| {
                let d = offs[f];
                frames[f].load_tile::<T>(c, x0 + d[0], y0 + d[1], buf);
            };
            let mut braw = [[0.0f32; T * T]; 4];
            let mut sigma2 = [0.0f32; 4];
            let mut mu = [0.0f32; 4];
            for c in 0..4 {
                load(0, c, &mut braw[c]);
                mu[c] = (braw[c].iter().sum::<f32>() / (T * T) as f32).max(0.0);
                sigma2[c] = (p.kappa * (s[c] * mu[c] + o[c])).max(1e-12);
            }
            // Noise of frame f in its gained units: g*S*x + g^2*O (00 §3, 05 §5.3 ★).
            let sigma2_f = |f: usize, c: usize| {
                let g = frames[f].gain;
                (p.kappa * (g * s[c] * mu[c] + g * g * o[c])).max(1e-12)
            };
            // Two real planes per complex FFT (channel pairs), spectra split afterwards.
            let spectra = |raw: &[[f32; T * T]; 4]| -> [[C; T * T]; 4] {
                let mut out = [[C::default(); T * T]; 4];
                for pair in 0..2 {
                    let mut x = [C::default(); T * T];
                    for k in 0..T * T {
                        let wk = w[k % T] * w[k / T];
                        x[k] = C { re: raw[2 * pair][k] * wk, im: raw[2 * pair + 1][k] * wk };
                    }
                    fft2d(&mut x, false);
                    let [a, b] = split(&x);
                    out[2 * pair] = a;
                    out[2 * pair + 1] = b;
                }
                out
            };
            let bh = spectra(&braw);
            let mut acc = bh; // frame 0 contributes B itself
            let mut neff = [1.0f32; 4];
            let mut zraw = [[0.0f32; T * T]; 4];
            for f in 1..n {
                // Per-frame mismatch over all 4 planes (REVIEW 1.6: normalized to 2*sigma^2).
                let mut m = 0.0;
                for c in 0..4 {
                    load(f, c, &mut zraw[c]);
                    let ss: f32 = braw[c].iter().zip(zraw[c].iter()).map(|(a, b)| (a - b) * (a - b)).sum();
                    // E[ss] = N * (sigma_0^2 + sigma_f^2) ~ 2*N*sigma^2 on a static tile.
                    m += ss / ((T * T) as f32 * (sigma2[c] + sigma2_f(f, c)));
                }
                let strength_t = lerp(p.lambda_t, p.lambda_t_min, smoothstep(p.theta_lo, p.theta_hi, m / 4.0));
                let zh = spectra(&zraw);
                for c in 0..4 {
                    let psd = 0.5 * (sigma2[c] + sigma2_f(f, c)) * win_sq_sum;
                    let k_t = strength_t * psd;
                    let (b, z, a_c) = (&bh[c], &zh[c], &mut acc[c]);
                    let mut a_sum = 0.0;
                    for &(k, _, wgt) in half_bins() {
                        let d = C { re: b[k].re - z[k].re, im: b[k].im - z[k].im };
                        let d2 = d.re * d.re + d.im * d.im;
                        let a = d2 / (d2 + k_t);
                        a_sum += a * wgt;
                        a_c[k].re += z[k].re + a * d.re;
                        a_c[k].im += z[k].im + a * d.im;
                    }
                    neff[c] += 1.0 - a_sum / (T * T) as f32;
                }
            }
            let inv_n = 1.0 / n as f32;
            let shaping = shaping();
            for c in 0..4 {
                let psd_t = sigma2[c] * win_sq_sum;
                // Spatial Wiener (05 §5.3 p.5) with noise shaping F = 1 + |w|/w_max (ASSUMED, [H16]).
                let k_s = p.lambda_s * psd_t / neff[c];
                for &(k, _, _) in half_bins() {
                    let (re, im) = (acc[c][k].re * inv_n, acc[c][k].im * inv_n);
                    let m2 = re * re + im * im;
                    let a_s = if k == 0 { 1.0 } else { m2 / (m2 + k_s * shaping[k]) };
                    acc[c][k] = C { re: re * a_s, im: im * a_s };
                }
                for &(k, m, wgt) in half_bins() {
                    if wgt > 1.0 {
                        acc[c][m] = C { re: acc[c][k].re, im: -acc[c][k].im };
                    }
                }
            }
            for pair in 0..2 {
                let (a, b) = (&acc[2 * pair], &acc[2 * pair + 1]);
                let mut x = [C::default(); T * T];
                for k in 0..T * T {
                    x[k] = C { re: a[k].re - b[k].im, im: a[k].im + b[k].re };
                }
                fft2d(&mut x, true);
                let (d0, d1) = (out_ptrs[2 * pair], out_ptrs[2 * pair + 1]);
                for j in 0..T {
                    let row = (STEP * ty + j) * padw + STEP * tx;
                    for i in 0..T {
                        let v = x[j * T + i];
                        unsafe {
                            *d0.get().add(row + i) += v.re / (T * T) as f32;
                            *d1.get().add(row + i) += v.im / (T * T) as f32;
                        }
                    }
                }
            }
            let accept = neff.iter().sum::<f32>() / 4.0;
            unsafe {
                *st_ptr.get().add(ty * ntx + tx) = (n as f32 / accept).clamp(1.0, n as f32);
                *ne_ptr.get().add(ty * ntx + tx) = accept;
            }
        }
    };
    // Even then odd tile rows: rows written by one tile row never overlap within a pass.
    par_for(nty.div_ceil(2), |i| tile_row(2 * i));
    par_for(nty / 2, |i| tile_row(2 * i + 1));

    let m_eff = neff_tiles.iter().sum::<f32>() / neff_tiles.len() as f32;
    let planes = std::array::from_fn(|c| {
        Img::from_fn(pw, ph, |x, y| out[c][(y + STEP) * padw + x + STEP])
    });
    Merged {
        planes,
        s: s.map(|v| v / m_eff),
        o: o.map(|v| v / m_eff),
        strength,
    }
}

/// Single-frame passthrough (05 §5.1 "no merge needed").
pub fn passthrough(f: &Frame, s: &[f32; 4], o: &[f32; 4]) -> Merged {
    Merged {
        planes: std::array::from_fn(|c| f.plane(c)),
        s: *s,
        o: *o,
        strength: Img::from_fn(f.pw.div_ceil(STEP) + 1, f.ph.div_ceil(STEP) + 1, |_, _| 1.0),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::raw_prep::{noise, synth_raw};

    #[test]
    fn fft_roundtrip_and_window_partition() {
        let mut t = [C::default(); T * T];
        for k in 0..T * T {
            t[k].re = (k as f32 * 0.37).sin();
        }
        let orig = t;
        fft2d(&mut t, false);
        // DC = sum
        let sum: f32 = orig.iter().map(|c| c.re).sum();
        assert!((t[0].re - sum).abs() < 1e-3);
        // Compare a few bins with the naive DFT.
        for (u, v) in [(1usize, 0usize), (3, 5), (15, 2)] {
            let (mut re, mut im) = (0.0f64, 0.0f64);
            for k in 0..T * T {
                let ph = -2.0 * std::f64::consts::PI * ((u * (k % T) + v * (k / T)) as f64) / 16.0;
                re += orig[k].re as f64 * ph.cos();
                im += orig[k].re as f64 * ph.sin();
            }
            let got = t[v * T + u];
            assert!((got.re as f64 - re).abs() < 1e-3 && (got.im as f64 - im).abs() < 1e-3, "bin {u},{v}");
        }
        fft2d(&mut t, true);
        for k in 0..T * T {
            assert!((t[k].re / 256.0 - orig[k].re).abs() < 1e-4);
        }
        let w = window();
        for n in 0..8 {
            assert!((w[n] + w[n + 8] - 1.0).abs() < 1e-6);
        }
        assert!((w[0] - 0.0096).abs() < 1e-4 && (w[7] - 0.9904).abs() < 1e-4);
    }

    #[test]
    fn packed_real_fft_split() {
        let (mut x, mut a, mut b) = ([C::default(); T * T], [C::default(); T * T], [C::default(); T * T]);
        for k in 0..T * T {
            let (u, v) = ((k as f32 * 0.37).sin(), (k as f32 * 0.11).cos());
            x[k] = C { re: u, im: v };
            a[k].re = u;
            b[k].re = v;
        }
        fft2d(&mut x, false);
        fft2d(&mut a, false);
        fft2d(&mut b, false);
        let [sa, sb] = split(&x);
        for &(k, _, _) in half_bins() {
            assert!((sa[k].re - a[k].re).abs() < 1e-3 && (sa[k].im - a[k].im).abs() < 1e-3);
            assert!((sb[k].re - b[k].re).abs() < 1e-3 && (sb[k].im - b[k].im).abs() < 1e-3);
        }
    }

    fn zero_field(pw: usize, ph: usize) -> Field {
        let (nx, ny) = (pw.div_ceil(8), ph.div_ceil(8));
        Field { nx, ny, step: 8, tile: 16, v: vec![[0.0; 2]; nx * ny] }
    }

    #[test]
    fn static_burst_reduces_noise_and_moving_object_is_rejected() {
        let (w, h, n) = (128usize, 128usize, 8usize);
        let sigma = 0.02f32;
        let mut seed = 99u64;
        let clean = |x: usize, _y: usize| 0.3 + 0.1 * ((x as f32) * 0.1).sin();
        let mut raws: Vec<Vec<u16>> = (0..n)
            .map(|f| {
                synth_raw(w, h, 0.0, 65535.0, |x, y| {
                    // a bright square moves in alt frames only
                    let obj = f > 0 && (40..60).contains(&x) && (40..60).contains(&y);
                    let v = if obj { 0.9 } else { clean(x, y) };
                    v + sigma * noise(&mut seed)
                })
            })
            .collect();
        let frames: Vec<Frame> = raws
            .iter_mut()
            .map(|r| Frame::new(r, w, h, w, 0, [0.0; 4], 65535.0, 1.0))
            .collect();
        let fields: Vec<Field> = (1..n).map(|_| zero_field(w / 2, h / 2)).collect();
        let s = [0.0; 4];
        let o = [sigma * sigma; 4];
        let m = merge(&frames, &fields, &s, &o, &DEFAULT_PARAMS);
        // Noise in the static region (plane coords 40..60 avoid the object at plane 20..30).
        let mut err = 0.0;
        let mut cnt = 0.0;
        for y in 40..60 {
            for x in 4..60 {
                let truth = clean(2 * x, 2 * y);
                err += (m.planes[0].d[y * 64 + x] - truth).powi(2);
                cnt += 1.0;
            }
        }
        let rms = (err / cnt as f32).sqrt();
        assert!(rms < sigma * 0.6, "rms {rms}");
        // Ghost: base has no object; merged value inside the object must stay near background.
        let v = m.planes[0].d[25 * 64 + 25];
        assert!((v - clean(50, 50)).abs() < 0.1, "ghost {v}");
        let m_eff = o[0] / m.o[0];
        assert!(m_eff > 3.0 && m_eff <= n as f32, "m_eff {m_eff}");
    }
}
