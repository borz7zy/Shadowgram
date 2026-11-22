// SPDX-FileCopyrightText: borz7zy
// SPDX-License-Identifier: MIT

use aes::Aes256;
use ctr::cipher::{KeyIvInit, StreamCipher};
use ring::digest::{Context, SHA256};
use serde::Deserialize;
use std::collections::BTreeMap;
use std::time::{Duration, Instant};
use subtle::ConstantTimeEq;
use zeroize::Zeroizing;

use super::sctp::SignalingSctp;

const SINGLE: u32 = 1 << 31;
const NEEDS_ACK: u32 = 1 << 30;
const COUNTER_MASK: u32 = NEEDS_ACK - 1;
const CUSTOM: u8 = 127;
const EMPTY: u8 = 254;
const ACK: u8 = 255;
const MAX_OUTGOING: usize = 16 * 1024;
pub(super) const MAX_INCOMING: usize = 128 * 1024;
const MAX_RECORDS: usize = 256;
const RETRY: Duration = Duration::from_millis(900);

#[derive(Clone, Copy, Deserialize)]
pub(super) enum Protocol {
    #[serde(rename = "10.0.0")]
    Sequenced,
    #[serde(rename = "11.0.0")]
    Sctp,
}

pub(super) struct Wire {
    cipher: Signaling,
    sctp: Option<Box<SignalingSctp>>,
}

impl Wire {
    pub fn new(key: Zeroizing<[u8; 256]>, outgoing: bool, protocol: Protocol) -> Option<Self> {
        let sctp = match protocol {
            Protocol::Sequenced => None,
            Protocol::Sctp => Some(Box::new(SignalingSctp::new(outgoing, Instant::now())?)),
        };
        Some(Self {
            cipher: Signaling::new(key, outgoing),
            sctp,
        })
    }

    pub fn send(&mut self, payload: &[u8], now: Instant) -> Option<Vec<Vec<u8>>> {
        if let Some(sctp) = &mut self.sctp {
            let encrypted = self.cipher.send_compressed(payload)?;
            sctp.send(encrypted, now)
        } else {
            Some(vec![self.cipher.send(payload, now)?])
        }
    }

    pub fn receive(
        &mut self,
        packet: Vec<u8>,
        now: Instant,
    ) -> Option<(Vec<Vec<u8>>, Vec<Vec<u8>>)> {
        if let Some(sctp) = &mut self.sctp {
            let (received, outgoing) = sctp.receive(packet, now)?;
            let decoded = received
                .into_iter()
                .filter_map(|bytes| self.cipher.receive_compressed(&bytes));
            Some((decoded.collect(), outgoing))
        } else {
            let received = self.cipher.receive(&packet)?;
            Some((received.messages, received.ack.into_iter().collect()))
        }
    }

    pub fn tick(&mut self, now: Instant) -> Option<Vec<Vec<u8>>> {
        if let Some(sctp) = &mut self.sctp {
            sctp.tick(now)
        } else {
            Some(self.cipher.retry(now))
        }
    }
}

#[derive(Default)]
struct ReplayWindow {
    newest: u32,
    seen: u64,
}

impl ReplayWindow {
    fn accept(&mut self, seq: u32) -> bool {
        let counter = seq & COUNTER_MASK;
        if counter == 0 {
            return false;
        }
        if counter > self.newest {
            self.seen = self.seen.checked_shl(counter - self.newest).unwrap_or(0);
            self.newest = counter;
        }
        let Some(bit) = 1u64.checked_shl(self.newest - counter) else {
            return false;
        };
        let fresh = self.seen & bit == 0;
        self.seen |= bit;
        fresh
    }
}

struct Pending {
    record: Vec<u8>,
    sent_at: Instant,
    retries: u8,
}

pub(super) struct Signaling {
    key: Zeroizing<[u8; 256]>,
    outgoing: bool,
    sequence: u32,
    received: ReplayWindow,
    pending: BTreeMap<u32, Pending>,
}

pub(super) struct Received {
    pub messages: Vec<Vec<u8>>,
    pub ack: Option<Vec<u8>>,
}

impl Signaling {
    pub fn new(key: Zeroizing<[u8; 256]>, outgoing: bool) -> Self {
        Self {
            key,
            outgoing,
            sequence: 0,
            received: ReplayWindow::default(),
            pending: BTreeMap::new(),
        }
    }

    pub fn send(&mut self, payload: &[u8], now: Instant) -> Option<Vec<u8>> {
        if payload.is_empty()
            || payload.len() + 30 > MAX_OUTGOING
            || self.pending.len() >= MAX_RECORDS
        {
            return None;
        }
        let seq = self.next()? | NEEDS_ACK;
        let mut record = Vec::with_capacity(9 + payload.len());
        record.extend_from_slice(&seq.to_be_bytes());
        record.push(CUSTOM);
        record.extend_from_slice(&(payload.len() as u32).to_be_bytes());
        record.extend_from_slice(payload);
        let packet = self.seal(&record);
        self.pending.insert(
            seq,
            Pending {
                record,
                sent_at: now,
                retries: 0,
            },
        );
        Some(packet)
    }

    pub fn retry(&mut self, now: Instant) -> Vec<Vec<u8>> {
        let mut due = Vec::new();
        self.pending.retain(|_, pending| {
            if now.duration_since(pending.sent_at) < RETRY {
                return true;
            }
            if pending.retries >= 5 {
                return false;
            }
            due.push(pending.record.clone());
            pending.sent_at = now;
            pending.retries += 1;
            true
        });
        due.into_iter()
            .filter_map(|record| self.service(&record))
            .collect()
    }

    pub fn receive(&mut self, data: &[u8]) -> Option<Received> {
        let clear = self.open(data)?;
        let records = parse(&clear)?;
        let mut messages = Vec::new();
        let mut acks = Vec::new();
        for (seq, record) in records {
            match record {
                Record::Ack => {
                    self.pending.remove(&seq);
                }
                Record::Empty => {
                    self.received.accept(seq);
                }
                Record::Custom(payload) => {
                    if seq & NEEDS_ACK != 0 {
                        acks.extend_from_slice(&seq.to_be_bytes());
                        acks.push(ACK);
                    }
                    if self.received.accept(seq) {
                        messages.push(payload.to_vec());
                    }
                }
            }
        }
        let ack = if acks.is_empty() {
            None
        } else {
            self.service(&acks)
        };
        Some(Received { messages, ack })
    }

    fn send_compressed(&mut self, data: &[u8]) -> Option<Vec<u8>> {
        use std::io::Write;
        if data.len() > MAX_INCOMING {
            return None;
        }
        let prefix = self.next()?.to_be_bytes().to_vec();
        let mut compressor = flate2::write::GzEncoder::new(prefix, flate2::Compression::fast());
        compressor.write_all(data).ok()?;
        let record = compressor.finish().ok()?;
        (record.len() + 16 <= MAX_INCOMING).then(|| self.seal(&record))
    }

    fn receive_compressed(&mut self, data: &[u8]) -> Option<Vec<u8>> {
        use std::io::Read;
        let clear = self.open(data)?;
        let seq = u32::from_be_bytes(clear[..4].try_into().ok()?);
        if !self.received.accept(seq) {
            return None;
        }
        let mut message = Vec::new();
        if clear[4..].starts_with(&[0x1f, 0x8b]) {
            flate2::read::GzDecoder::new(&clear[4..])
                .take((MAX_INCOMING + 1) as u64)
                .read_to_end(&mut message)
                .ok()?;
        } else {
            message.extend_from_slice(&clear[4..]);
        }
        (message.len() <= MAX_INCOMING).then_some(message)
    }

    fn open(&self, data: &[u8]) -> Option<Vec<u8>> {
        if !(21..=MAX_INCOMING).contains(&data.len()) {
            return None;
        }
        let x = self.offset(false);
        let (msg_key, encrypted) = data.split_at(16);
        let mut clear = encrypted.to_vec();
        self.crypt(x, msg_key, &mut clear);
        if !bool::from(self.message_key(x, &clear).ct_eq(msg_key)) {
            return None;
        }
        Some(clear)
    }

    fn next(&mut self) -> Option<u32> {
        if self.sequence == COUNTER_MASK {
            return None;
        }
        self.sequence += 1;
        Some(self.sequence)
    }

    fn service(&mut self, entries: &[u8]) -> Option<Vec<u8>> {
        let mut bytes = Vec::with_capacity(5 + entries.len());
        bytes.extend_from_slice(&self.next()?.to_be_bytes());
        bytes.push(EMPTY);
        bytes.extend_from_slice(entries);
        Some(self.seal(&bytes))
    }

    fn offset(&self, send: bool) -> usize {
        if self.outgoing == send {
            128
        } else {
            136
        }
    }

    fn message_key(&self, x: usize, body: &[u8]) -> [u8; 16] {
        hash(&self.key[88 + x..120 + x], body)[8..24]
            .try_into()
            .unwrap()
    }

    fn seal(&self, clear: &[u8]) -> Vec<u8> {
        let x = self.offset(true);
        let msg_key = self.message_key(x, clear);
        let mut packet = Vec::with_capacity(msg_key.len() + clear.len());
        packet.extend_from_slice(&msg_key);
        packet.extend_from_slice(clear);
        self.crypt(x, &msg_key, &mut packet[16..]);
        packet
    }

    fn crypt(&self, x: usize, msg_key: &[u8], bytes: &mut [u8]) {
        let a = Zeroizing::new(hash(msg_key, &self.key[x..x + 36]));
        let b = Zeroizing::new(hash(&self.key[x + 40..x + 76], msg_key));
        let mut key = Zeroizing::new([0u8; 32]);
        key[..8].copy_from_slice(&a[..8]);
        key[8..24].copy_from_slice(&b[8..24]);
        key[24..].copy_from_slice(&a[24..]);
        let mut iv = [0u8; 16];
        iv[..4].copy_from_slice(&b[..4]);
        iv[4..12].copy_from_slice(&a[8..16]);
        iv[12..].copy_from_slice(&b[24..28]);
        ctr::Ctr128BE::<Aes256>::new((&*key).into(), (&iv).into()).apply_keystream(bytes);
    }
}

fn hash(first: &[u8], second: &[u8]) -> [u8; 32] {
    let mut ctx = Context::new(&SHA256);
    ctx.update(first);
    ctx.update(second);
    ctx.finish().as_ref().try_into().unwrap()
}

enum Record<'a> {
    Empty,
    Ack,
    Custom(&'a [u8]),
}

fn parse(mut body: &[u8]) -> Option<Vec<(u32, Record<'_>)>> {
    let mut result = Vec::new();
    while !body.is_empty() {
        if result.len() == MAX_RECORDS {
            return None;
        }
        let (seq, rest) = body.split_at_checked(4)?;
        let seq = u32::from_be_bytes(seq.try_into().ok()?);
        let (&kind, rest) = rest.split_first()?;
        body = rest;
        let record = match kind {
            EMPTY if result.is_empty() && seq & NEEDS_ACK == 0 => Record::Empty,
            ACK if !result.is_empty() => Record::Ack,
            CUSTOM => {
                let (len, rest) = body.split_at_checked(4)?;
                let len = u32::from_be_bytes(len.try_into().ok()?) as usize;
                let (content, remaining) = rest.split_at_checked(len)?;
                body = remaining;
                Record::Custom(content)
            }
            _ => return None,
        };
        if seq & SINGLE != 0 && (!result.is_empty() || !body.is_empty()) {
            return None;
        }
        result.push((seq, record));
    }
    Some(result)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn packet_authentication_ack_replay_and_retransmission() {
        let key = std::array::from_fn(|i| i as u8);
        let mut caller = Signaling::new(Zeroizing::new(key), true);
        let mut callee = Signaling::new(Zeroizing::new(key), false);
        let now = Instant::now();
        let packet = caller.send(b"offer", now).unwrap();
        assert_eq!(
            packet,
            [
                0x5f, 0xe6, 0x24, 0xc1, 0xf0, 0xf9, 0xa2, 0x30, 0x0d, 0x77, 0xf1, 0xbb, 0x02, 0x3d,
                0xa8, 0xf8, 0x4f, 0xd6, 0xfc, 0x94, 0xf9, 0xe2, 0x24, 0xb5, 0xd2, 0x27, 0xc9, 0x84,
                0x38, 0x91,
            ]
        );
        let mut bad = packet.clone();
        bad[16] ^= 1;
        assert!(callee.receive(&bad).is_none());
        assert!(caller.receive(&packet).is_none());
        assert_eq!(
            callee.receive(&packet).unwrap().messages,
            [b"offer".to_vec()]
        );
        let retransmission = caller.retry(now + RETRY).pop().unwrap();
        let result = callee.receive(&retransmission).unwrap();
        assert!(result.messages.is_empty());
        caller.receive(&result.ack.unwrap()).unwrap();
        assert!(caller.retry(now + RETRY * 2).is_empty());
    }

    #[test]
    fn accepts_reordered_packets_within_window() {
        let mut window = ReplayWindow::default();
        assert!(window.accept(130));
        assert!(window.accept(68));
        assert!(!window.accept(NEEDS_ACK | 68));
        assert!(!window.accept(66));
        assert!(window.accept(200));
        assert!(!window.accept(130));
    }

    #[test]
    fn compressed_offer_and_answer_cross_the_sctp_channel() {
        let key = std::array::from_fn(|i| i as u8);
        let mut caller = Wire::new(Zeroizing::new(key), true, Protocol::Sctp).unwrap();
        let mut callee = Wire::new(Zeroizing::new(key), false, Protocol::Sctp).unwrap();
        let start = Instant::now();
        let offer = b"encrypted offer over the SCTP association";
        let answer = b"encrypted answer on the same stream";
        let initial = caller.send(offer, start).unwrap();
        assert!(!initial.is_empty());
        // Drop the first INIT and drive the retry timer. No sockets or TDLib are needed.
        let mut to_callee = Vec::new();
        let mut to_caller = Vec::new();
        let mut received_offer = false;
        for tick in 0..200 {
            let now = start + Duration::from_millis(100 * tick);
            to_callee.extend(caller.tick(now).unwrap());
            to_caller.extend(callee.tick(now).unwrap());
            for packet in to_callee.drain(..) {
                let (messages, response) = callee.receive(packet, now).unwrap();
                to_caller.extend(response);
                if !messages.is_empty() {
                    assert!(!received_offer);
                    assert_eq!(messages, [offer.to_vec()]);
                    received_offer = true;
                    to_caller.extend(callee.send(answer, now).unwrap());
                }
            }
            for packet in to_caller.drain(..) {
                let (messages, response) = caller.receive(packet, now).unwrap();
                to_callee.extend(response);
                if !messages.is_empty() {
                    assert!(received_offer);
                    assert_eq!(messages, [answer.to_vec()]);
                    return;
                }
            }
        }
        panic!("SCTP did not deliver the answer");
    }
}
