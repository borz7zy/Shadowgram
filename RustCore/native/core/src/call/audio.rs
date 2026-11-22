// SPDX-FileCopyrightText: borz7zy
// SPDX-License-Identifier: MIT

use opus_pure::{Application, OpusDecoder, OpusEncoder, MAX_PACKET_BYTES, MAX_PACKET_SAMPLES};
use ring::rand::{SecureRandom, SystemRandom};
use rtc::rtp::{header::Header, packet::Packet};
use std::collections::{BTreeMap, VecDeque};

pub const RATE: i32 = 48_000;
pub const FRAME: usize = RATE as usize / 50;
pub type Pcm = [i16; FRAME];
const JITTER_PACKETS: usize = 16;
const INITIAL_BUFFER: usize = 3;

pub(super) struct Capture {
    encoder: OpusEncoder,
    sequence: u16,
    timestamp: u32,
    pub ssrc: u32,
    first: bool,
}

impl Capture {
    pub fn new() -> Option<Self> {
        let mut encoder = OpusEncoder::new(RATE, 1, Application::Voip).ok()?;
        encoder.bitrate_bps = 32_000;
        let mut seed = [0u8; 10];
        SystemRandom::new().fill(&mut seed).ok()?;
        Some(Self {
            encoder,
            sequence: u16::from_ne_bytes(seed[..2].try_into().ok()?),
            timestamp: u32::from_ne_bytes(seed[2..6].try_into().ok()?),
            ssrc: u32::from_ne_bytes(seed[6..].try_into().ok()?).max(1),
            first: true,
        })
    }

    pub fn encode(&mut self, pcm: &Pcm) -> Option<Packet> {
        let mut payload = vec![0; MAX_PACKET_BYTES];
        let len = self.encoder.encode_s16(pcm, FRAME, &mut payload).ok()?;
        payload.truncate(len);
        let packet = Packet {
            header: Header {
                version: 2,
                marker: self.first,
                payload_type: 111,
                sequence_number: self.sequence,
                timestamp: self.timestamp,
                ssrc: self.ssrc,
                ..Default::default()
            },
            payload: payload.into(),
        };
        self.sequence = self.sequence.wrapping_add(1);
        self.timestamp = self.timestamp.wrapping_add(FRAME as u32);
        self.first = false;
        Some(packet)
    }
}

// Keep the decoder in RTP sequence order and bound buffering to keep latency predictable.
pub(super) struct Playout {
    decoder: OpusDecoder,
    packets: BTreeMap<i64, Packet>,
    cursor: Option<i64>,
    decoded: VecDeque<i16>,
    empty_frames: usize,
    decode_buffer: Vec<i16>,
}

impl Playout {
    pub fn new() -> Option<Self> {
        Some(Self {
            decoder: OpusDecoder::new(RATE, 1).ok()?,
            packets: BTreeMap::new(),
            cursor: None,
            decoded: VecDeque::new(),
            empty_frames: 0,
            decode_buffer: vec![0; MAX_PACKET_SAMPLES],
        })
    }

    pub fn push(&mut self, packet: Packet) {
        if packet.payload.len() > MAX_PACKET_BYTES || packet.payload.is_empty() {
            return;
        }
        let sequence = packet.header.sequence_number;
        let base = self
            .cursor
            .or_else(|| self.packets.first_key_value().map(|(&seq, _)| seq));
        let index = base.map_or(i64::from(sequence), |base| {
            base + i64::from(sequence.wrapping_sub(base as u16) as i16)
        });
        if self.cursor.is_some_and(|next| index < next) {
            return;
        }
        if self.packets.len() < JITTER_PACKETS {
            self.packets.entry(index).or_insert(packet);
        }
    }

    pub fn frame(&mut self) -> Pcm {
        let mut pcm = [0; FRAME];
        if self.cursor.is_none() && self.packets.len() >= INITIAL_BUFFER {
            self.cursor = self.packets.first_key_value().map(|(&seq, _)| seq);
        }
        while self.decoded.len() < FRAME {
            let Some(seq) = self.cursor else { break };
            let packet = self.packets.remove(&seq);
            self.cursor = Some(seq + 1);
            let decoded = if let Some(packet) = packet {
                self.empty_frames = 0;
                self.decoder.decode_s16(
                    &packet.payload,
                    MAX_PACKET_SAMPLES,
                    &mut self.decode_buffer,
                )
            } else {
                self.empty_frames += 1;
                if self.empty_frames >= 5 && self.packets.is_empty()
                    || self
                        .packets
                        .first_key_value()
                        .is_some_and(|(&next, _)| next - seq > JITTER_PACKETS as i64)
                {
                    self.cursor = None;
                    self.empty_frames = 0;
                    break;
                }
                self.decoder.decode_s16(&[], FRAME, &mut self.decode_buffer)
            };
            match decoded {
                Ok(samples) if samples != 0 => self.decoded.extend(&self.decode_buffer[..samples]),
                _ => break,
            }
        }
        let available = self.decoded.len().min(FRAME);
        for (sample, decoded) in pcm[..available]
            .iter_mut()
            .zip(self.decoded.drain(..available))
        {
            *sample = decoded;
        }
        pcm
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn opus_voice_plays_through_small_packet_reordering() {
        let mut capture = Capture::new().unwrap();
        let mut playout = Playout::new().unwrap();
        let voice = std::array::from_fn(|i| ((i as f32 * 0.09).sin() * 12000.0) as i16);
        let frames: Vec<_> = (0..4).map(|_| capture.encode(&voice).unwrap()).collect();
        for index in [1, 0, 2, 3] {
            playout.push(frames[index].clone());
        }
        let audible = playout
            .frame()
            .into_iter()
            .chain(playout.frame())
            .any(|s| s.abs() > 500);
        assert!(audible);
        assert_eq!(playout.packets.len(), 2);
    }
}
