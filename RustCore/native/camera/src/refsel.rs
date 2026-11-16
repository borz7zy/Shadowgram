// SPDX-FileCopyrightText: borz7zy
// SPDX-License-Identifier: MIT

//! 03-reference-selection: sharpness metric, base frame choice, blurry-frame rejection.
//!
//! ponytail: camera-blur term h(blur_px) from gyro+OIS is fixed to 1 — CameraX does not hand
//! us per-frame gyro/OIS samples. Feed STATISTICS_OIS_SAMPLES + gyro here if lucky imaging
//! picks shaken frames on device.

use crate::raw_prep::{Frame, GB, GR};

/// Sharpness score (03 §3.1). Returns None for all-black / all-white frames.
/// Noise correction uses 4*Var (REVIEW 1.3): E[dx^2 + dy^2] = 4 sigma^2 for forward differences.
pub fn sharpness(f: &Frame, s: &[f32; 4], o: &[f32; 4]) -> Option<f32> {
    let sg = 0.5 * (s[GR] + s[GB]);
    let og = 0.5 * (o[GR] + o[GB]);
    // Quarter resolution of green (03 §3.5): every 2nd quad.
    let g = |x: usize, y: usize| 0.5 * (f.get(GR, x as isize, y as isize) + f.get(GB, x as isize, y as isize));
    let (mut grad, mut noise, mut n, mut sum) = (0.0f64, 0.0f64, 0usize, 0.0f64);
    let mut total = 0usize;
    for y in (0..f.ph.saturating_sub(2)).step_by(2) {
        for x in (0..f.pw.saturating_sub(2)).step_by(2) {
            let c = g(x, y);
            sum += c as f64;
            total += 1;
            if c >= 0.98 || c <= 0.0 {
                continue;
            }
            let dx = g(x + 2, y) - c;
            let dy = g(x, y + 2) - c;
            grad += (dx * dx + dy * dy) as f64;
            // Var of a mean of 2 greens.
            noise += (4.0 * (sg * c + og) / 2.0) as f64;
            n += 1;
        }
    }
    let mean = sum / total.max(1) as f64;
    if n == 0 || !(0.002..0.98).contains(&mean) {
        return None;
    }
    Some(((grad - noise).max(0.0) / n as f64) as f32)
}

/// Base = sharpest among the first `candidates` valid frames ([H16] lucky imaging, 3).
pub fn select_base(scores: &[Option<f32>], candidates: usize) -> usize {
    scores
        .iter()
        .enumerate()
        .filter_map(|(i, s)| s.map(|v| (i, v)))
        .take(candidates)
        .max_by(|a, b| a.1.total_cmp(&b.1))
        .map_or(0, |(i, _)| i)
}

/// Which frames to keep (03 §3.3): drop invalid frames and frames with score < 0.85 * base,
/// never more than half of the burst; nothing is dropped for blur in Night mode.
pub fn keep_frames(scores: &[Option<f32>], base: usize, night: bool) -> Vec<bool> {
    let base_score = scores[base].unwrap_or(0.0);
    let mut keep: Vec<bool> = scores.iter().map(|s| s.is_some()).collect();
    keep[base] = true;
    if night {
        return keep;
    }
    let mut blurry: Vec<usize> = (0..scores.len())
        .filter(|&i| i != base && keep[i] && scores[i].unwrap() < 0.85 * base_score)
        .collect();
    blurry.sort_by(|&a, &b| scores[a].unwrap().total_cmp(&scores[b].unwrap()));
    for &i in blurry.iter().take(scores.len() / 2) {
        keep[i] = false;
    }
    keep
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::raw_prep::synth_raw;

    #[test]
    fn sharper_frame_wins() {
        let pattern = |x: usize, y: usize, blur: bool| {
            let v = if ((x / 8) + (y / 8)) % 2 == 0 { 0.7 } else { 0.2 };
            if blur { 0.45 + (v - 0.45) * 0.3 } else { v }
        };
        let mut a = synth_raw(128, 128, 64.0, 1023.0, |x, y| pattern(x, y, true));
        let mut b = synth_raw(128, 128, 64.0, 1023.0, |x, y| pattern(x, y, false));
        let mut c = synth_raw(128, 128, 64.0, 1023.0, |_, _| 0.0);
        let fa = Frame::new(&mut a, 128, 128, 128, 0, [64.0; 4], 1023.0, 1.0);
        let fb = Frame::new(&mut b, 128, 128, 128, 0, [64.0; 4], 1023.0, 1.0);
        let fc = Frame::new(&mut c, 128, 128, 128, 0, [64.0; 4], 1023.0, 1.0);
        let (s, o) = ([1e-4; 4], [1e-6; 4]);
        let scores = [sharpness(&fa, &s, &o), sharpness(&fb, &s, &o), sharpness(&fc, &s, &o)];
        assert!(scores[2].is_none());
        assert_eq!(select_base(&scores, 3), 1);
        assert_eq!(keep_frames(&scores, 1, false), vec![false, true, false]);
        assert_eq!(keep_frames(&scores, 1, true), vec![true, true, false]);
    }
}
