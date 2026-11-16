// SPDX-FileCopyrightText: borz7zy
// SPDX-License-Identifier: MIT

//! WebM video stickers / custom emoji: VP9 with an alpha channel.
//!
//! WebM stores alpha as a second VP9 stream inside each block's
//! `BlockAdditional` (id 1). MediaCodec ignores it, so we demux ourselves
//! ([`demux`]) and run two libvpx decoders — colour and alpha — merging the
//! alpha decoder's luma into the RGBA output.

mod vpx;

use media::{err, Image, Result};

/// One video frame: colour packet + optional alpha packet.
struct Packet {
    track: u64,
    ts_ms: u64,
    color: Vec<u8>,
    alpha: Option<Vec<u8>>,
}

/// Frame-by-frame player over an in-memory WebM. Not `Sync`; one per view.
pub struct WebmDecoder {
    packets: Vec<Packet>,
    color: vpx::Decoder,
    alpha: Option<vpx::Decoder>,
    next: usize,
}

impl WebmDecoder {
    pub fn new(data: &[u8]) -> Result<Self> {
        let packets = demux(data)?;
        if packets.is_empty() {
            return Err(err("webm: no video frames"));
        }
        let has_alpha = packets.iter().any(|p| p.alpha.is_some());
        Ok(Self {
            packets,
            color: vpx::Decoder::new()?,
            alpha: if has_alpha { Some(vpx::Decoder::new()?) } else { None },
            next: 0,
        })
    }

    pub fn frame_count(&self) -> usize {
        self.packets.len()
    }

    /// Timestamp of the last frame + one average frame interval.
    pub fn duration_ms(&self) -> u64 {
        let n = self.packets.len() as u64;
        let last = self.packets.last().map_or(0, |p| p.ts_ms);
        if n > 1 { last + last / (n - 1) } else { last }
    }

    /// Next frame (scaled into `max_w×max_h`) and its timestamp, or `None` at
    /// the end — call [`rewind`](Self::rewind) to loop.
    pub fn next_frame(&mut self, max_w: u32, max_h: u32) -> Result<Option<(Image, u64)>> {
        let Some(p) = self.packets.get(self.next) else { return Ok(None) };
        self.next += 1;
        let color = self.color.decode(&p.color)?;
        let alpha = match (&mut self.alpha, &p.alpha) {
            (Some(d), Some(a)) => Some(d.decode(a)?),
            _ => None,
        };
        let mut img = color.to_rgba(alpha.as_ref());
        img = media::downscale(img, max_w, max_h);
        Ok(Some((img, p.ts_ms)))
    }

    /// Back to frame 0. VP9 needs a fresh decoder state to restart from the
    /// keyframe, and a new context is cheap.
    pub fn rewind(&mut self) -> Result<()> {
        self.color = vpx::Decoder::new()?;
        if self.alpha.is_some() {
            self.alpha = Some(vpx::Decoder::new()?);
        }
        self.next = 0;
        Ok(())
    }
}

/// First frame as a still — sticker previews.
pub fn decode(data: &[u8], max_w: u32, max_h: u32) -> Result<Image> {
    WebmDecoder::new(data)?.next_frame(max_w, max_h)?.map(|(i, _)| i).ok_or_else(|| err("webm: empty"))
}

// ---- minimal EBML walk ------------------------------------------------------
//
// Only what Telegram stickers use: one video track, no lacing, SimpleBlock or
// BlockGroup{Block, BlockAdditions}. Master elements we care about are entered,
// everything else skipped by size.

const SEGMENT: u32 = 0x1853_8067;
const INFO: u32 = 0x1549_A966;
const TIMESTAMP_SCALE: u32 = 0x2A_D7B1;
const CLUSTER: u32 = 0x1F43_B675;
const CLUSTER_TS: u32 = 0xE7;
const SIMPLE_BLOCK: u32 = 0xA3;
const BLOCK_GROUP: u32 = 0xA0;
const BLOCK: u32 = 0xA1;
const BLOCK_ADDITIONS: u32 = 0x75A1;
const BLOCK_MORE: u32 = 0xA6;
const BLOCK_ADD_ID: u32 = 0xEE;
const BLOCK_ADDITIONAL: u32 = 0xA5;

/// EBML variable-length integer. `strip` drops the length marker (sizes);
/// IDs keep it.
fn vint(b: &[u8], pos: &mut usize, strip: bool) -> Option<(u64, bool)> {
    let first = *b.get(*pos)?;
    let len = first.leading_zeros() as usize + 1;
    if len > 8 || *pos + len > b.len() {
        return None;
    }
    let mut v = if strip { (first as u64) & (0xFF >> len) } else { first as u64 };
    for &x in &b[*pos + 1..*pos + len] {
        v = (v << 8) | x as u64;
    }
    *pos += len;
    // All value bits set = "unknown size" (live-streamed Segment/Cluster).
    let unknown = strip && v == (1u64 << (7 * len)) - 1;
    Some((v, unknown))
}

fn uint(b: &[u8]) -> u64 {
    b.iter().fold(0, |a, &x| (a << 8) | x as u64)
}

fn demux(b: &[u8]) -> Result<Vec<Packet>> {
    let mut out = Vec::new();
    let mut scale_ns = 1_000_000u64; // TimestampScale default: 1 ms
    let mut cluster_ts = 0u64;
    walk(b, 0, b.len(), &mut |id, body| match id {
        TIMESTAMP_SCALE => scale_ns = uint(body).max(1),
        CLUSTER_TS => cluster_ts = uint(body),
        SIMPLE_BLOCK => push_block(&mut out, body, None, cluster_ts, scale_ns),
        BLOCK_GROUP => {
            let (mut block, mut alpha) = (None, None);
            walk(body, 0, body.len(), &mut |id, d| match id {
                BLOCK => block = Some(d),
                BLOCK_MORE => {
                    let (mut add_id, mut add) = (1, None);
                    walk(d, 0, d.len(), &mut |id, x| match id {
                        BLOCK_ADD_ID => add_id = uint(x),
                        BLOCK_ADDITIONAL => add = Some(x),
                        _ => {}
                    });
                    if add_id == 1 {
                        alpha = add;
                    }
                }
                _ => {}
            });
            if let Some(bl) = block {
                push_block(&mut out, bl, alpha, cluster_ts, scale_ns);
            }
        }
        _ => {}
    })
    .ok_or_else(|| err("webm: malformed EBML"))?;
    Ok(out)
}

/// Visit leaf elements in `b[start..end]`, descending into the masters that
/// lead to blocks. Containers inside a BlockGroup are entered by the caller.
///
/// Bytes that aren't a valid ID or size end only the current master, the
/// caller carries on after it (like ffmpeg): some sticker tools patch Info in
/// place and leave zero padding inside it, and the clusters after it are fine.
fn walk<'a>(b: &'a [u8], start: usize, end: usize, f: &mut dyn FnMut(u32, &'a [u8])) -> Option<()> {
    let mut pos = start;
    while pos < end {
        let Some((id, _)) = vint(b, &mut pos, false) else { break };
        let Some((size, unknown)) = vint(b, &mut pos, true) else { break };
        let body_end = if unknown { end } else { pos.checked_add(size as usize)?.min(end) };
        match id as u32 {
            SEGMENT | INFO | CLUSTER | BLOCK_ADDITIONS => walk(b, pos, body_end, f)?,
            other => f(other, &b[pos..body_end]),
        }
        pos = body_end;
    }
    Some(())
}

fn push_block(out: &mut Vec<Packet>, bl: &[u8], alpha: Option<&[u8]>, cluster_ts: u64, scale_ns: u64) {
    let mut pos = 0;
    let Some((track, _)) = vint(bl, &mut pos, true) else { return };
    if bl.len() < pos + 3 || bl[pos + 2] & 0x06 != 0 {
        return; // truncated, or laced (never used for video)
    }
    // Stickers have one track; anything else in the file is not ours.
    if out.first().is_some_and(|p| p.track != track) {
        return;
    }
    let rel = i16::from_be_bytes([bl[pos], bl[pos + 1]]) as i64;
    let ts = (cluster_ts as i64 + rel).max(0) as u64;
    out.push(Packet {
        track,
        ts_ms: ts * scale_ns / 1_000_000,
        color: bl[pos + 3..].to_vec(),
        alpha: alpha.map(<[u8]>::to_vec),
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decodes_alpha_sticker() {
        let data = include_bytes!("../../testdata/sticker.webm");
        let mut d = WebmDecoder::new(data).unwrap();
        assert_eq!(d.frame_count(), 30);
        assert!((900..=1100).contains(&d.duration_ms()));
        let (img, ts) = d.next_frame(0, 0).unwrap().unwrap();
        assert_eq!((img.width, img.height, ts), (128, 128, 0));
        let a = |x: u32, y: u32| img.rgba[((y * 128 + x) * 4 + 3) as usize];
        assert!(a(64, 64) > 240, "centre of the circle is opaque");
        assert!(a(2, 2) < 15, "corner is transparent");
        let mut n = 1;
        while d.next_frame(64, 64).unwrap().is_some() {
            n += 1;
        }
        assert_eq!(n, 30);
        d.rewind().unwrap();
        assert_eq!(d.next_frame(64, 64).unwrap().unwrap().0.width, 64);
    }

    /// Zero padding inside Info (seen in a real Telegram sticker) must not drop the whole file.
    #[test]
    fn skips_junk_inside_info() {
        let mut data = include_bytes!("../../testdata/sticker.webm").to_vec();
        const PAD: u64 = 6;
        // Grow an element's size field in place, keeping its encoded length.
        let grow = |data: &mut Vec<u8>, id_at: usize, id_len: usize| -> usize {
            let mut pos = id_at + id_len;
            let start = pos;
            let (size, unknown) = vint(data, &mut pos, true).unwrap();
            if !unknown {
                let len = pos - start;
                let v = (size + PAD) | (1u64 << (7 * len)); // value + length marker
                data[start..pos].copy_from_slice(&v.to_be_bytes()[8 - len..]);
            }
            pos + size as usize // end of the old body
        };
        let seg = data.windows(4).position(|w| w == SEGMENT.to_be_bytes()).unwrap();
        grow(&mut data, seg, 4);
        // The real Info, not its ID quoted inside SeekHead: step over Segment's children.
        let mut pos = seg + 4;
        vint(&data, &mut pos, true).unwrap();
        let info = loop {
            let at = pos;
            let (id, _) = vint(&data, &mut pos, false).unwrap();
            let (size, _) = vint(&data, &mut pos, true).unwrap();
            if id as u32 == INFO {
                break at;
            }
            pos += size as usize;
        };
        let info_end = grow(&mut data, info, 4);
        data.splice(info_end..info_end, [0u8; PAD as usize]);

        let d = WebmDecoder::new(&data).unwrap();
        assert_eq!(d.frame_count(), 30);
    }
}
