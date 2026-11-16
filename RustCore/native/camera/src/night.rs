// SPDX-FileCopyrightText: borz7zy
// SPDX-License-Identifier: MIT

//! 12-night-sight: exposure schedule from motion (12 §12.1, [L19]).
//!
//! ponytail: only camera ego-motion (gyro RMS) is metered — no scene-motion optical flow on
//! preview frames, no temporal binning (12 §12.2, needs a gapless ZSL ring) and no partial-burst
//! EC (12 §12.3). Add preview flow if moving subjects come out blurred.

/// Allowed motion blur per frame, px @4032 (ASSUMED, 12 §12.9: 1-2 px).
const BLUR_MAX_PX: f32 = 1.5;
/// Gyro RMS below which we consider the phone on a tripod (ASSUMED).
const TRIPOD_RAD_S: f32 = 0.01;

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct NightPlan {
    pub exposure_ms: f32,
    pub iso: f32,
    pub frames: u32,
    pub tripod: bool,
}

/// `tet` = exposure_ms * ISO chosen by the HAL AE; `omega` = gyro RMS (rad/s);
/// `focal_px` = focal length in pixels at the FACT reference width of 4032 px.
pub fn plan(tet: f32, omega: f32, focal_px: f32, iso_min: f32, iso_max: f32, exp_max_ms: f32) -> NightPlan {
    let tripod = omega < TRIPOD_RAD_S;
    // FACT: tripod 1000 ms/frame, 15000 ms total; handheld ASSUMED [L19] 333 ms, 6000 ms.
    let (t_cap, t_total) = if tripod { (1000.0, 15000.0) } else { (333.0, 6000.0) };
    let v = omega.max(1e-4) * focal_px / 1000.0; // px/ms
    // On a tripod the residual gyro reading is sensor noise: only the caps apply.
    let t_motion = if tripod { f32::INFINITY } else { BLUR_MAX_PX / v };
    // No point in exposing longer than needed to reach the target at base ISO.
    let t_needed = tet / iso_min;
    let t = t_motion.min(t_cap).min(exp_max_ms).min(t_needed).max(1.0);
    let iso = (tet / t).clamp(iso_min, iso_max);
    let frames = ((t_total / t).floor() as u32).clamp(6, 15);
    NightPlan { exposure_ms: t, iso, frames, tripod }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn handheld_vs_tripod() {
        // dark scene: AE wants 66 ms at ISO 3200
        let tet = 66.0 * 3200.0;
        let hand = plan(tet, 0.05, 3000.0, 50.0, 6400.0, 2000.0);
        // 0.05 rad/s * 3000 px = 0.15 px/ms -> 10 ms for 1.5 px
        assert!((hand.exposure_ms - 10.0).abs() < 0.5, "{hand:?}");
        assert_eq!(hand.iso, 6400.0);
        assert_eq!(hand.frames, 15);
        let tri = plan(tet, 0.001, 3000.0, 50.0, 6400.0, 2000.0);
        assert!(tri.tripod && tri.exposure_ms == 1000.0 && tri.frames == 15);
        assert!((tri.iso - 211.2).abs() < 1.0);
        // bright scene never exceeds what base ISO needs
        let bright = plan(10.0 * 50.0, 0.001, 3000.0, 50.0, 6400.0, 2000.0);
        assert_eq!(bright.exposure_ms, 10.0);
    }
}
