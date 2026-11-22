// SPDX-FileCopyrightText: borz7zy
// SPDX-License-Identifier: MIT

use ring::digest::{Context, SHA256};

pub const HOP_MS: usize = 10;
pub const MAX_FRAMES: usize = 30 * 60 * 1000 / HOP_MS;
const RATE: usize = 6000;
const HOP: usize = RATE * HOP_MS / 1000;
const FFT: usize = 256;

#[derive(Clone, Copy)]
struct Features {
    rms: f32,
    bass: f32,
    flux: f32,
}

pub struct Analysis {
    rate: usize,
    channels: usize,
    encoding: usize,
    phase: usize,
    sum: f64,
    samples: usize,
    dc: f32,
    waveform: [f32; FFT],
    window: [f32; FFT],
    real: [f32; FFT],
    imag: [f32; FFT],
    twiddle: [(f32, f32); FFT / 2],
    previous: [f32; FFT / 2],
    frames: Vec<Features>,
    hash: Context,
    hash_block: [u8; HOP * 2],
}

pub struct Analyzed {
    pub hash: [u8; 32],
    pub rate: usize,
    pub channels: usize,
    pub duration_us: u64,
    frames: Vec<Features>,
}

impl Analysis {
    pub fn new(rate: usize, channels: usize, encoding: usize) -> Option<Self> {
        if !(8000..=192000).contains(&rate) || !(1..=16).contains(&channels)
            || !matches!(encoding, 1..=5)
        {
            return None;
        }
        let mut hash = Context::new(&SHA256);
        hash.update(b"Shadowgram canonical PCM v1: mono s16le @6000Hz\0");
        Some(Self {
            rate, channels, encoding, phase: 0, sum: 0.0, samples: 0, dc: 0.0,
            waveform: [0.0; FFT],
            window: std::array::from_fn(|i| {
                0.5 - 0.5 * (std::f32::consts::TAU * i as f32 / FFT as f32).cos()
            }),
            real: [0.0; FFT],
            imag: [0.0; FFT],
            twiddle: std::array::from_fn(|i| {
                let phase = -std::f32::consts::TAU * i as f32 / FFT as f32;
                (phase.cos(), phase.sin())
            }),
            previous: [0.0; FFT / 2],
            frames: Vec::new(),
            hash, hash_block: [0; HOP * 2],
        })
    }

    pub fn feed(&mut self, bytes: &[u8]) -> Option<()> {
        let width = [0, 1, 2, 3, 4, 4][self.encoding];
        let size = width * self.channels;
        if bytes.len() % size != 0 {
            return None;
        }
        for frame in bytes.chunks_exact(size) {
            let mono = frame.chunks_exact(width)
                .map(|pcm| sample(self.encoding, pcm))
                .sum::<f64>() / self.channels as f64;
            // Integrate source samples over an exact rational resampling interval.
            let first = RATE.min(self.rate - self.phase);
            self.sum += mono * first as f64;
            self.phase += first;
            if self.phase == self.rate {
                let output = (self.sum / self.rate as f64).clamp(-1.0, 1.0);
                self.push((output * 32767.0).round() as i16)?;
                self.phase = RATE - first;
                self.sum = mono * self.phase as f64;
            }
        }
        Some(())
    }

    fn push(&mut self, sample: i16) -> Option<()> {
        if self.samples >= HOP * MAX_FRAMES {
            return None;
        }
        let offset = self.samples % HOP * 2;
        self.hash_block[offset..offset + 2].copy_from_slice(&sample.to_le_bytes());
        let value = sample as f32 / 32767.0;
        self.dc += 0.009 * (value - self.dc);
        self.waveform[self.samples % FFT] = value - self.dc;
        self.samples += 1;
        if self.samples % HOP == 0 {
            self.hash.update(&self.hash_block);
            self.measure();
        }
        Some(())
    }

    fn measure(&mut self) {
        let available = self.samples.min(FFT);
        let mut energy = 0.0;
        self.imag.fill(0.0);
        for i in 0..FFT {
            let value = if i < FFT - available { 0.0 } else {
                self.waveform[(self.samples + i) % FFT]
            };
            if i >= FFT - HOP {
                energy += value * value;
            }
            self.real[i.reverse_bits() >> (usize::BITS - FFT.trailing_zeros())] =
                value * self.window[i];
        }
        for stage in 1..=FFT.trailing_zeros() {
            let length = 1 << stage;
            let half = length / 2;
            for block in (0..FFT).step_by(length) {
                for k in 0..half {
                    let a = block + k;
                    let b = a + half;
                    let (cos, sin) = self.twiddle[k * FFT / length];
                    let re = self.real[b] * cos - self.imag[b] * sin;
                    let im = self.real[b] * sin + self.imag[b] * cos;
                    self.real[b] = self.real[a] - re;
                    self.imag[b] = self.imag[a] - im;
                    self.real[a] += re;
                    self.imag[a] += im;
                }
            }
        }
        let mut low = 0.0;
        let mut flux = 0.0;
        for bin in 1..FFT / 2 {
            let magnitude = self.real[bin].hypot(self.imag[bin]) * (4.0 / FFT as f32);
            if (40 * FFT..=180 * FFT).contains(&(bin * RATE)) {
                low += magnitude * magnitude;
            }
            flux += (magnitude - self.previous[bin]).max(0.0);
            self.previous[bin] = magnitude;
        }
        self.frames.push(Features { rms: (energy / HOP as f32).sqrt(), bass: low.sqrt(), flux });
    }

    pub fn finish(mut self) -> Option<Analyzed> {
        if self.samples == 0 {
            return None;
        }
        let remainder = self.samples % HOP;
        if remainder != 0 {
            self.hash.update(&self.hash_block[..remainder * 2]);
            self.measure();
        }
        let digest = self.hash.finish();
        let mut hash = [0; 32];
        hash.copy_from_slice(digest.as_ref());
        Some(Analyzed {
            hash,
            rate: self.rate,
            channels: self.channels,
            duration_us: self.samples as u64 * 1_000_000 / RATE as u64,
            frames: self.frames,
        })
    }
}

fn sample(encoding: usize, b: &[u8]) -> f64 {
    match encoding {
        1 => (f64::from(b[0]) - 128.0) / 128.0,
        2 => f64::from(i16::from_le_bytes([b[0], b[1]])) / 32768.0,
        3 => f64::from(i32::from_le_bytes([0, b[0], b[1], b[2]])) / 2147483648.0,
        4 => f64::from(i32::from_le_bytes([b[0], b[1], b[2], b[3]])) / 2147483648.0,
        _ => {
            let value = f32::from_le_bytes([b[0], b[1], b[2], b[3]]);
            if value.is_finite() { f64::from(value.clamp(-1.0, 1.0)) } else { 0.0 }
        }
    }
}

struct Profile {
    weights: [f32; 3],
    gain: f32,
    attack: f32,
    release: f32,
    compression: f32,
    limit: f32,
}

const PROFILES: [Profile; 3] = [
    Profile { weights: [0.38, 0.18, 0.44], gain: 0.65, attack: 0.58, release: 0.45, compression: 1.8, limit: 0.65 },
    Profile { weights: [0.50, 0.30, 0.20], gain: 0.92, attack: 0.88, release: 0.55, compression: 1.4, limit: 0.87 },
    Profile { weights: [0.53, 0.34, 0.13], gain: 1.15, attack: 1.0, release: 0.7, compression: 1.15, limit: 1.0 },
];

impl Analyzed {
    pub fn synthesize(&self) -> [Vec<u8>; 3] {
        let mut outputs = std::array::from_fn(|_| Vec::with_capacity(self.frames.len()));
        let mut envelopes = [0.0; 3];
        let mut continuous = [0; 3];
        let mut rest = [0; 3];
        let mut level = 0.04f32;
        let mut flux_floor = 0.025f32;
        let mut last_onset = None;
        let mut period = 0.0f32;
        let mut stable = 0u32;

        for (index, frame) in self.frames.iter().enumerate() {
            let previous = index.checked_sub(1).map_or(0.0, |i| self.frames[i].rms);
            let transient = (frame.flux - flux_floor * 1.8).max(0.0)
                + (frame.rms - previous).max(0.0) * 2.4;
            let peak = self.frames.get(index + 1).is_none_or(|next| frame.flux >= next.flux);
            let mut beat = 0.0;
            if peak && transient > 0.065 && frame.rms > 0.01 &&
                last_onset.is_none_or(|last| index - last >= 18) {
                if let Some(last) = last_onset {
                    let interval = (index - last) as f32;
                    if (24.0..=100.0).contains(&interval) {
                        if (period - interval).abs() < period * 0.23 {
                            stable += 1;
                            beat = if stable >= 2 { 0.18 } else { 0.0 };
                            period += (interval - period) * 0.22;
                        } else {
                            period = interval;
                            stable = 0;
                        }
                    } else {
                        stable = 0;
                    }
                }
                last_onset = Some(index);
            }
            level += (frame.rms - level) * if frame.rms > level { 0.035 } else { 0.004 };
            flux_floor += (frame.flux - flux_floor) * 0.035;
            let scale = 0.26 / level.clamp(0.016, 0.5);
            // The FFT window trails the RMS hop by about 15 ms at this sample rate.
            let bass = self.frames.get(index + 2).unwrap_or(frame).bass;
            let components = [
                (bass * scale * 2.0).min(1.4),
                (transient * scale * 1.6 + beat).min(1.4),
                (frame.rms * scale).min(1.4),
            ];
            for (i, profile) in PROFILES.iter().enumerate() {
                let input = components.iter().zip(profile.weights).map(|(x, w)| x * w).sum::<f32>()
                    * profile.gain;
                let target = if frame.rms < 0.004 { 0.0 } else {
                    (input / (1.0 + (profile.compression - 1.0) * input)).min(profile.limit)
                };
                let envelope = &mut envelopes[i];
                *envelope += (target - *envelope) * if target > *envelope { profile.attack } else { profile.release };
                let mut amplitude = if target == 0.0 || *envelope < 0.045 { 0 } else {
                    (*envelope * 255.0).round() as u8
                };
                if rest[i] > 0 {
                    rest[i] -= 1;
                    amplitude = 0;
                }
                if amplitude == 0 {
                    continuous[i] = 0;
                } else {
                    continuous[i] += 1;
                    if continuous[i] >= 50 {
                        rest[i] = 7;
                    }
                }
                outputs[i].push(amplitude);
            }
        }
        outputs
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn analyze(frequency: f32, level: f32) -> Analyzed {
        let mut analyzer = Analysis::new(48_000, 1, 2).unwrap();
        let pcm: Vec<_> = (0..24_000).flat_map(|i| {
            ((level * (std::f32::consts::TAU * frequency * i as f32 / 48000.0).sin()
                * 32767.0) as i16).to_le_bytes()
        }).collect();
        for chunk in pcm.chunks(4096) { analyzer.feed(chunk).unwrap(); }
        analyzer.finish().unwrap()
    }

    #[test]
    fn bass_and_noise_gate() {
        let low = analyze(90.0, 0.25);
        let high = analyze(900.0, 0.25);
        assert!(low.frames[25].bass > high.frames[25].bass * 15.0);
        assert!(analyze(90.0, 0.0005).synthesize()[1].iter().all(|&value| value == 0));
        assert_eq!(analyze(0.0, 0.0).duration_us, 500_000);
    }

    #[test]
    fn canonical_hash_ignores_chunking_and_dual_mono() {
        let mut mono = Analysis::new(48_000, 1, 2).unwrap();
        let mut stereo = Analysis::new(48_000, 2, 2).unwrap();
        mono.feed(&[1, 10].repeat(160)).unwrap();
        for _ in 0..160 { stereo.feed(&[1, 10, 1, 10]).unwrap(); }
        assert_eq!(mono.finish().unwrap().hash, stereo.finish().unwrap().hash);
    }
}
