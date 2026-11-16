// SPDX-FileCopyrightText: borz7zy
// SPDX-License-Identifier: MIT

//! 00-overview §1: the full burst pipeline, RAW16 burst in, sRGB ARGB_8888 out.

use crate::align::{align, pyramid, Base, Field};
use crate::color::{lsc_ccm, Lsc};
use crate::denoise;
use crate::demosaic::{demosaic, luma};
use crate::img::Img;
use crate::merge::{self, WienerParams};
use crate::raw_prep::{self, rggb_vec_index, Frame, GB, GR};
use crate::refsel;
use crate::sharpen::{guided_upsample, sharpen};
use crate::superres::superres;
use crate::tonemap;

#[derive(Clone, Copy, PartialEq, Debug)]
pub enum Mode {
    /// HDR+: underexposed burst + Wiener merge (05).
    Hdr,
    /// MFSR merge (06).
    SuperRes,
    /// Night Sight: long frames, no blur rejection, stronger temporal merge (12).
    Night,
}

pub struct Params {
    pub mode: Mode,
    pub width: usize,
    pub height: usize,
    pub stride: usize,
    pub cfa: i32,
    pub white_level: f32,
    /// Camera2 [R, G_even, G_odd, B] order.
    pub noise_s: [f32; 4],
    pub noise_o: [f32; 4],
    pub wb: [f32; 4],
    /// WB'd sensor RGB -> linear sRGB, row-major.
    pub ccm: [f32; 9],
    pub r_hdr: f32,
    /// Clockwise rotation to make the output upright (0/90/180/270).
    pub rotation: i32,
}

pub struct FrameIn<'a> {
    pub data: &'a mut [u16],
    /// SENSOR_DYNAMIC_BLACK_LEVEL / BLACK_LEVEL_PATTERN, raster 2x2 order.
    pub black_level: [f32; 4],
    /// exposure_ms * ISO.
    pub tet: f32,
}

/// Low-res branch factor 2^k (k = 1, ASSUMED).
const LOW_RES_FACTOR: usize = 2;
/// Exposure mismatch tolerance for frame rejection (03 §3.3, ASSUMED K = 2).
const TET_TOLERANCE: f32 = 2.0;

fn canonical(v: &[f32; 4], oy: usize) -> [f32; 4] {
    std::array::from_fn(|c| v[rggb_vec_index(c, oy)])
}

pub fn run(frames_in: Vec<FrameIn>, p: &Params, lsc: Option<Lsc>) -> (Vec<u32>, usize, usize) {
    let mut t = crate::log::Timer::new();
    crate::log::info(&format!(
        "{:?}: {} frames {}x{}, {} threads",
        p.mode,
        frames_in.len(),
        p.width,
        p.height,
        std::thread::available_parallelism().map_or(0, |v| v.get())
    ));
    let mut frames: Vec<(Frame, f32)> = frames_in
        .into_iter()
        .map(|f| {
            let tet = f.tet;
            (Frame::new(f.data, p.width, p.height, p.stride, p.cfa, f.black_level, p.white_level, 1.0), tet)
        })
        .collect();
    let oy = frames[0].0.oy;
    let s = canonical(&p.noise_s, oy);
    let o = canonical(&p.noise_o, oy);
    let wb = canonical(&p.wb, oy);
    let night = p.mode == Mode::Night;

    // 03: base selection, reorder base to index 0, rejection.
    let scores: Vec<Option<f32>> = {
        let out = std::sync::Mutex::new(vec![None; frames.len()]);
        crate::img::par_for(frames.len(), |i| {
            let v = refsel::sharpness(&frames[i].0, &s, &o);
            out.lock().unwrap()[i] = v;
        });
        out.into_inner().unwrap()
    };
    let base = refsel::select_base(&scores, if night { frames.len() } else { 3 });
    let keep = refsel::keep_frames(&scores, base, night);
    let base_tet = frames[base].1;
    frames.swap(0, base);
    let mut keep = keep;
    keep.swap(0, base);
    let mut frames: Vec<Frame> = frames
        .into_iter()
        .zip(keep)
        .filter(|((_, tet), k)| *k && (tet / base_tet).max(base_tet / tet) <= TET_TOLERANCE)
        .map(|((mut f, tet), _)| {
            f.set_gain(base_tet / tet);
            f
        })
        .collect();

    t.lap("refsel");
    // 10 §10.1: HAL AWB gains; gray-world on the base frame if the HAL gave none.
    let wb = if wb.iter().any(|&g| g <= 0.0) || wb.iter().all(|&g| (g - 1.0).abs() < 1e-3) {
        crate::color::gray_world(&raw_prep::planes(&frames[0]))
    } else {
        wb
    };

    // 02: hot pixels across the burst.
    raw_prep::suppress_hot_pixels(&mut frames, &wb);

    t.lap("hot_pixels");
    // 04: align every alt frame to the base.
    let base_al = Base::new(frames[0].gray());
    let fields: Vec<Field> = frames[1..].iter().map(|f| align(&base_al, &pyramid(f.gray()))).collect();
    drop(base_al);

    t.lap("align");
    // 08 §8.2: moire / false-color detection from inter-frame chroma consistency.
    let moire = crate::moire::detect(&frames, &fields, &wb, &s, &o);
    t.lap("moire_detect");

    // 05 / 06: merge.
    let (mut rgb, ms, mo, strength) = if p.mode == Mode::SuperRes && frames.len() > 2 {
        let (rgb, m_eff) = superres(&frames, &fields, &wb, &s, &o, Some(&moire));
        let ones = Img::from_fn(frames[0].pw.div_ceil(8) + 1, frames[0].ph.div_ceil(8) + 1, |_, _| 1.0);
        (rgb, s.map(|v| v / m_eff), o.map(|v| v / m_eff), ones)
    } else {
        let params = if night {
            // 12 §12.4 ASSUMED: stronger temporal + spatial merge at low SNR.
            WienerParams { lambda_t: 12.0, lambda_s: 1.5, ..merge::DEFAULT_PARAMS }
        } else {
            merge::DEFAULT_PARAMS
        };
        let m = if frames.len() == 1 {
            merge::passthrough(&frames[0], &s, &o)
        } else {
            merge::merge(&frames, &fields, &s, &o, &params)
        };
        t.lap("merge");
        // 07: demosaic.
        (demosaic(&m.planes, &wb), m.s, m.o, m.strength)
    };
    t.lap("demosaic|superres");
    moire.suppress(&mut rgb);
    t.lap("moire_suppress");
    let mu = raw_prep::mean_green(&frames[0]);
    let snr = raw_prep::snr(mu, 0.5 * (ms[GR] + ms[GB]), 0.5 * (mo[GR] + mo[GB]));
    let tune = denoise::tune(snr.log2());
    drop(frames);

    // 08: full-res luma denoise; low-res branch input.
    let (sy, oy_n) = denoise::y_noise(&ms, &mo, &wb);
    let y = luma(&rgb);
    let (fw, fh) = (rgb[0].w, rgb[0].h);
    let (lw, lh) = (fw / LOW_RES_FACTOR, fh / LOW_RES_FACTOR);
    let low: [Img; 3] = std::array::from_fn(|c| rgb[c].down2());
    drop(rgb);
    let y_dn = denoise::luma(&y, sy, oy_n, &strength, 16.0, tune.luma_strength, tune.revert);
    drop(y);
    t.lap("luma_denoise");
    let y_low = luma(&low);
    let u = Img::from_fn(lw, lh, |x, yy| low[2].d[yy * lw + x] - y_low.d[yy * lw + x]);
    let v = Img::from_fn(lw, lh, |x, yy| low[0].d[yy * lw + x] - y_low.d[yy * lw + x]);
    let k2 = (LOW_RES_FACTOR * LOW_RES_FACTOR) as f32;
    let (u, v) = denoise::chroma(&y_low, &u, &v, sy / k2, oy_n / k2, tune.chroma_strength, tune.chroma_levels);
    let y_low_dn = y_dn.down2();
    let mut low: [Img; 3] = {
        let yl = &y_low_dn;
        let r = Img::from_fn(lw, lh, |x, yy| v.d[yy * lw + x] + yl.d[yy * lw + x]);
        let b = Img::from_fn(lw, lh, |x, yy| u.d[yy * lw + x] + yl.d[yy * lw + x]);
        let g = Img::from_fn(lw, lh, |x, yy| {
            let i = yy * lw + x;
            2.0 * (yl.d[i] - 0.25 * r.d[i] - 0.25 * b.d[i])
        });
        [r, g, b]
    };

    t.lap("chroma_denoise");
    // 10, 09: color and tone in low resolution.
    lsc_ccm(&mut low, lsc.as_ref(), &p.ccm);
    tonemap::fuse(&mut low, p.r_hdr);
    tonemap::dehaze(&mut low);
    tonemap::global(&mut low, night);

    t.lap("color+tonemap");
    // 11: guided upsampling (sqrt-luma guides, ASSUMED) + sharpening.
    let sq = |img: &Img| Img::from_fn(img.w, img.h, |x, yy| img.d[yy * img.w + x].max(0.0).sqrt());
    let mut out = guided_upsample(&sq(&y_low_dn), &low, &sq(&y_dn));
    drop(low);
    drop(y_dn);
    let core = 0.6 * (sy * 0.18 + oy_n).max(0.0).sqrt().max(0.002);
    sharpen(&mut out, tune.sharpen, core);
    t.lap("guided+sharpen");
    let res = pack(&out, p.rotation);
    t.lap("pack");
    res
}

/// ARGB_8888 with clockwise rotation.
fn pack(rgb: &[Img; 3], rotation: i32) -> (Vec<u32>, usize, usize) {
    let (w, h) = (rgb[0].w, rgb[0].h);
    let rot = rotation.rem_euclid(360);
    let (ow, oh) = if rot == 90 || rot == 270 { (h, w) } else { (w, h) };
    let mut px = vec![0u32; ow * oh];
    let q = |v: f32| (v.clamp(0.0, 1.0) * 255.0 + 0.5) as u32;
    let ptr = crate::img::SyncPtr(px.as_mut_ptr());
    crate::img::par_for(h, |y| {
        for x in 0..w {
            let i = y * w + x;
            let (dx, dy) = match rot {
                90 => (h - 1 - y, x),
                180 => (w - 1 - x, h - 1 - y),
                270 => (y, w - 1 - x),
                _ => (x, y),
            };
            let v = 0xFF00_0000 | (q(rgb[0].d[i]) << 16) | (q(rgb[1].d[i]) << 8) | q(rgb[2].d[i]);
            // SAFETY: the rotation is a bijection, every output index is written once.
            unsafe { *ptr.get().add(dy * ow + dx) = v };
        }
    });
    (px, ow, oh)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::raw_prep::{noise, synth_raw};

    /// Host profile: `GCAM_TIMING=1 cargo test --release profile_night -- --ignored --nocapture`.
    #[test]
    #[ignore]
    fn profile_night_15x12mp() {
        profile(Mode::Night, 15);
    }

    #[test]
    #[ignore]
    fn profile_superres_10x12mp() {
        profile(Mode::SuperRes, 10);
    }

    fn profile(mode: Mode, n: usize) {
        let (w, h) = (4080usize, 3072usize);
        let mut raws: Vec<Vec<u16>> = (0..n)
            .map(|f| {
                let mut seed = 1 + f as u64;
                synth_raw(w, h, 64.0, 1023.0, |x, y| {
                    let (x, y) = (x as f32 - 0.7 * f as f32, y as f32 + 0.3 * f as f32);
                    0.05 + 0.04 * ((x * 0.013).sin() * (y * 0.011).cos()) + 0.02 * ((x * 0.31).sin()).max(0.0)
                        + 0.004 * noise(&mut seed)
                })
            })
            .collect();
        let frames: Vec<FrameIn> = raws.iter_mut().map(|d| FrameIn { data: d, black_level: [64.0; 4], tet: 1000.0 }).collect();
        let p = Params {
            mode,
            width: w,
            height: h,
            stride: w,
            cfa: 0,
            white_level: 1023.0,
            noise_s: [2e-4; 4],
            noise_o: [1e-6; 4],
            wb: [2.0, 1.0, 1.0, 1.6],
            ccm: [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0],
            r_hdr: 2.0,
            rotation: 0,
        };
        let t = std::time::Instant::now();
        let (px, _, _) = run(frames, &p, None);
        eprintln!("total {:?}, {} px", t.elapsed(), px.len());
    }

    #[test]
    fn end_to_end_burst() {
        let (w, h, n) = (160usize, 120usize, 4usize);
        let mut seed = 3u64;
        let scene = |x: f32, y: f32| if ((x / 20.0) as i32 + (y / 20.0) as i32) % 2 == 0 { 0.08 } else { 0.25 };
        let mut raws: Vec<Vec<u16>> = (0..n)
            .map(|f| {
                let sx = f as f32; // 1 px drift per frame
                synth_raw(w, h, 64.0, 1023.0, |x, y| scene(x as f32 - sx, y as f32) + 0.01 * noise(&mut seed))
            })
            .collect();
        let frames: Vec<FrameIn> = raws.iter_mut().map(|d| FrameIn { data: d, black_level: [64.0; 4], tet: 100.0 }).collect();
        let p = Params {
            mode: Mode::Hdr,
            width: w,
            height: h,
            stride: w,
            cfa: 0,
            white_level: 1023.0,
            noise_s: [1e-4; 4],
            noise_o: [1e-4; 4],
            wb: [1.0; 4],
            ccm: [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0],
            r_hdr: 2.0,
            rotation: 90,
        };
        let (px, ow, oh) = run(frames, &p, None);
        assert_eq!((ow, oh), (h, w));
        // Output is gray (WB = 1, identity CCM) and the checkerboard survives.
        let lum = |i: usize| ((px[i] >> 8) & 0xFF) as i32;
        let (lo, hi) = px.iter().enumerate().fold((255, 0), |(lo, hi), (i, _)| (lo.min(lum(i)), hi.max(lum(i))));
        assert!(hi - lo > 60, "contrast {lo}..{hi}");
        let c = px[(oh / 2) * ow + ow / 2];
        let (r, g, b) = ((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF);
        assert!((r as i32 - g as i32).abs() < 12 && (b as i32 - g as i32).abs() < 12, "{r} {g} {b}");
    }
}
