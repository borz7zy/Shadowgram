// SPDX-FileCopyrightText: borz7zy
// SPDX-License-Identifier: MIT

use super::dsp::{HOP_MS, MAX_FRAMES};
use flate2::{bufread::ZlibDecoder, write::ZlibEncoder, Compression};
use std::io::{Read, Write};

pub const FORMAT: u8 = 1;
pub const GENERATOR: u8 = 1;
pub const MAX_BLOB: usize = MAX_FRAMES * 2 + 128;
const HEADER: usize = 56;
pub const MAX_PLAN_MS: i64 = 6000;

pub fn encode(hash: &[u8; 32], duration_us: u64, profile: u8, frames: &[u8]) -> Option<Vec<u8>> {
    if frames.is_empty() || frames.len() > MAX_FRAMES || profile > 2 {
        return None;
    }
    let mut bytes = Vec::with_capacity(HEADER + 64);
    bytes.extend_from_slice(b"HPF\0");
    bytes.extend_from_slice(&[FORMAT, GENERATOR, profile, HOP_MS as u8]);
    bytes.extend_from_slice(hash);
    bytes.extend_from_slice(&duration_us.to_le_bytes());
    bytes.extend_from_slice(&(frames.len() as u32).to_le_bytes());
    let mut crc = crc32fast::Hasher::new();
    crc.update(&bytes);
    crc.update(frames);
    bytes.extend_from_slice(&crc.finalize().to_le_bytes());
    let mut compressor = ZlibEncoder::new(bytes, Compression::fast());
    compressor.write_all(frames).ok()?;
    let bytes = compressor.finish().ok()?;
    (bytes.len() <= MAX_BLOB).then_some(bytes)
}

pub fn decode(bytes: &[u8], hash: &[u8; 32], profile: u8) -> Option<Vec<u8>> {
    if !(HEADER + 5..=MAX_BLOB).contains(&bytes.len()) ||
        &bytes[..8] != [b'H', b'P', b'F', 0, FORMAT, GENERATOR, profile, HOP_MS as u8] ||
        &bytes[8..40] != hash
    {
        return None;
    }
    let duration = u64::from_le_bytes(bytes[40..48].try_into().ok()?);
    let count = u32::from_le_bytes(bytes[48..52].try_into().ok()?) as usize;
    if !(1..=MAX_FRAMES).contains(&count) || duration == 0 ||
        duration.div_ceil(HOP_MS as u64 * 1000) != count as u64
    {
        return None;
    }
    let checksum = u32::from_le_bytes(bytes[52..56].try_into().ok()?);
    let mut frames = Vec::with_capacity(count);
    let mut decoder = ZlibDecoder::new(&bytes[56..]);
    (&mut decoder).take(count as u64 + 1).read_to_end(&mut frames).ok()?;
    let mut crc = crc32fast::Hasher::new();
    crc.update(&bytes[..HEADER - 4]);
    crc.update(&frames);
    (frames.len() == count && decoder.total_in() == (bytes.len() - 56) as u64 &&
        crc.finalize() == checksum).then_some(frames)
}

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum PlanMode {
    Amplitude,
    Binary,
    NotificationBinary,
    NotificationAmplitude,
}

/// Alternating duration and amplitude. On basic hardware, short bins and hysteresis
/// limit motor switching. Quantization reduces the size of the Android waveform.
pub fn plan(frames: &[u8], start: i64, length: i64, speed: f32, intensity: i32, mode: PlanMode) -> Vec<i32> {
    if frames.is_empty() || !speed.is_finite() || !(0.25..=4.0).contains(&speed) {
        return Vec::new();
    }
    let end = start.saturating_add((length.clamp(0, MAX_PLAN_MS) as f32 * speed).round() as i64)
        .min((frames.len() * HOP_MS) as i64);
    let intensity = intensity.clamp(0, 100);
    let basic = matches!(mode, PlanMode::Binary | PlanMode::NotificationBinary);
    let threshold = if mode == PlanMode::NotificationBinary && end > start.max(0) {
        // Keep quiet notification sounds tactile on systems that only accept on/off patterns.
        let active_frames = &frames[start.max(0) as usize / HOP_MS..(end as usize).div_ceil(HOP_MS)];
        let peak = i32::from(active_frames.iter().copied().max().unwrap_or(0)) * intensity / 100;
        (peak * 3 / 5).clamp(1, 60)
    } else {
        60
    };
    let release_threshold = (threshold * 38 / 60).max(1);
    let mut result = Vec::new();
    let step = match mode {
        PlanMode::NotificationAmplitude => 20,
        PlanMode::Binary | PlanMode::NotificationBinary => 25,
        PlanMode::Amplitude => HOP_MS as i64,
    };
    let mut at = start;
    let mut output_at = 0;
    let mut on = false;
    while at < end {
        let next = (at + step - at.rem_euclid(step)).min(end);
        let amplitude = if at < 0 {
            0
        } else {
            let first = at as usize / HOP_MS;
            let last = (next as usize).div_ceil(HOP_MS).min(frames.len());
            let amplitude = i32::from(frames[first..last].iter().copied().max().unwrap_or(0))
                * intensity / 100;
            if basic {
                on = amplitude >= if on { release_threshold } else { threshold };
                if on { 255 } else { 0 }
            } else {
                (amplitude + 4) / 8 * 8
            }
        }.min(255);
        let output_next = ((next - start) as f32 / speed).round() as i32;
        let duration = output_next - output_at;
        if duration > 0 {
            let size = result.len();
            if result.last() == Some(&amplitude) {
                result[size - 2] += duration;
            } else {
                result.extend_from_slice(&[duration, amplitude]);
            }
        }
        output_at = output_next;
        at = next;
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trip_rejects_corrupt_and_wrong_version() {
        let hash = [4; 32];
        let frames = [42, 0, 254];
        let mut blob = encode(&hash, 29_999, 2, &frames).unwrap();
        assert_eq!(decode(&blob, &hash, 2).unwrap(), frames);
        assert!(decode(&blob, &hash, 1).is_none());
        assert!(decode(&blob, &[3; 32], 2).is_none());
        blob[52] ^= 1;
        assert!(decode(&blob, &hash, 2).is_none());
        blob[4] += 1;
        assert!(decode(&blob, &hash, 2).is_none());
    }

    #[test]
    fn plan_includes_end_of_five_second_preview() {
        let mut frames = vec![0; 500];
        frames[490..].fill(128);
        let effect = plan(&frames, -100, 6000, 1.0, 100, PlanMode::Amplitude);
        assert_eq!(effect, [5000, 0, 100, 128]);
    }

    #[test]
    fn quiet_notification_retains_pulses_on_binary_channels() {
        let frames = [0, 0, 0, 32, 32, 0, 0, 0, 0, 0, 32, 32, 0, 0, 0];
        assert_eq!(plan(&frames, 0, 150, 1.0, 75, PlanMode::Binary), [150, 0]);
        assert_eq!(
            plan(&frames, 0, 150, 1.0, 75, PlanMode::NotificationBinary),
            [25, 0, 25, 255, 50, 0, 25, 255, 25, 0],
        );
        assert_eq!(
            plan(&[0; 15], 0, 150, 1.0, 75, PlanMode::NotificationBinary),
            [150, 0],
        );
    }

    #[test]
    fn amplitude_notification_covers_the_sound_with_bounded_channel_waveform() {
        let frames: Vec<_> = (0..600)
            .map(|i| if (i / 2) % 2 == 0 { 30 } else { 140 })
            .collect();
        let effect = plan(&frames, 0, 6_000, 1.0, 100, PlanMode::NotificationAmplitude);
        assert_eq!(effect.len() / 2, 300);
        assert_eq!(effect.chunks_exact(2).map(|pair| pair[0]).sum::<i32>(), 6_000);
    }

    #[test]
    fn seek_speed_and_basic_timing() {
        let frames = [0, 0, 100, 100, 0, 0, 0, 180];
        assert_eq!(plan(&frames, 20, 25, 2.0, 100, PlanMode::Amplitude), [10, 104, 15, 0]);
        let plan = plan(&frames, -20, 100, 1.0, 100, PlanMode::Binary);
        assert_eq!(plan.chunks_exact(2).map(|v| v[0]).sum::<i32>(), 100);
        assert!(plan.iter().skip(1).step_by(2).all(|&x| x == 0 || x == 255));
    }
}
