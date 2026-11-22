// SPDX-FileCopyrightText: borz7zy
// SPDX-License-Identifier: MIT

use super::signaling::MAX_INCOMING;
use rtc::shared::TransportProtocol;
use rtc_sctp::{
    Association, AssociationHandle, ClientConfig, DatagramEvent, Endpoint, EndpointConfig, Event,
    Payload, PayloadProtocolIdentifier, ServerConfig, TransportConfig,
};
use std::collections::VecDeque;
use std::net::{Ipv4Addr, SocketAddr};
use std::sync::Arc;
use std::time::Instant;

const PORT: u16 = 5000;
const STREAM: u16 = 0;
const MAX_QUEUED: usize = 96;
const MAX_BUFFERED: usize = 512 * 1024;

/// Provides the reliable SCTP association over TDLib signaling updates. Its addresses are labels
/// for rtc-sctp; all actual datagrams go through the Telegram signaling API.
pub(super) struct SignalingSctp {
    endpoint: Endpoint,
    association: Option<(AssociationHandle, Association)>,
    remote: SocketAddr,
    connected: bool,
    queued: VecDeque<Vec<u8>>,
}

impl SignalingSctp {
    pub fn new(outgoing: bool, now: Instant) -> Option<Self> {
        let local = SocketAddr::from((Ipv4Addr::new(127, 0, 0, 1), PORT));
        let remote = SocketAddr::from((Ipv4Addr::new(127, 0, 0, 2), PORT));
        let transport = TransportConfig::default()
            .with_max_message_size(MAX_INCOMING as u32)
            .with_max_receive_buffer_size(MAX_BUFFERED as u32)
            .with_max_num_inbound_streams(1)
            .with_max_num_outbound_streams(1);
        let server = (!outgoing).then(|| Arc::new(ServerConfig::new(transport.clone())));
        let mut endpoint = Endpoint::new(
            local,
            TransportProtocol::UDP,
            Arc::new(EndpointConfig::default()),
            server,
        );
        let association = if outgoing {
            Some(
                endpoint
                    .connect(now, ClientConfig::new(transport), remote)
                    .ok()?,
            )
        } else {
            None
        };
        Some(Self {
            endpoint,
            association,
            remote,
            connected: false,
            queued: VecDeque::new(),
        })
    }

    pub fn send(&mut self, packet: Vec<u8>, now: Instant) -> Option<Vec<Vec<u8>>> {
        if self.queued.len() >= MAX_QUEUED {
            return None;
        }
        self.queued.push_back(packet);
        self.tick(now)
    }

    pub fn receive(
        &mut self,
        packet: Vec<u8>,
        now: Instant,
    ) -> Option<(Vec<Vec<u8>>, Vec<Vec<u8>>)> {
        if !(16..=MAX_INCOMING).contains(&packet.len()) {
            return Some((Vec::new(), Vec::new()));
        }
        if let Some((handle, event)) = self.endpoint.handle(now, self.remote, None, packet.into()) {
            match event {
                DatagramEvent::NewAssociation(association) if self.association.is_none() => {
                    self.endpoint.set_server_config(None);
                    self.association = Some((handle, association));
                }
                DatagramEvent::AssociationEvent(event) => {
                    if let Some((active, association)) = &mut self.association {
                        if handle == *active {
                            association.handle_event(event);
                        }
                    }
                }
                _ => {}
            }
        }
        let outgoing = self.tick(now)?;
        let mut incoming = Vec::new();
        if let Some((_, association)) = &mut self.association {
            if let Ok(mut stream) = association.stream(STREAM) {
                while let Some(message) = stream.read_sctp().ok()? {
                    if message.ppi == PayloadProtocolIdentifier::Binary {
                        if let Ok(bytes) = message.to_payload(MAX_INCOMING) {
                            incoming.push(bytes.to_vec());
                        }
                    }
                }
            }
        }
        Some((incoming, outgoing))
    }

    pub fn tick(&mut self, now: Instant) -> Option<Vec<Vec<u8>>> {
        let mut outgoing = Vec::new();
        let Some((handle, association)) = &mut self.association else {
            return Some(outgoing);
        };
        if association
            .poll_timeout()
            .is_some_and(|deadline| deadline <= now)
        {
            association.handle_timeout(now);
        }
        while let Some(event) = association.poll() {
            match event {
                Event::Connected => self.connected = true,
                Event::HandshakeFailed { .. } | Event::AssociationLost { .. } => return None,
                _ => {}
            }
        }
        if self.connected {
            if association.stream(STREAM).is_err() {
                association
                    .open_stream(STREAM, PayloadProtocolIdentifier::Binary)
                    .ok()?;
            }
            let mut stream = association.stream(STREAM).ok()?;
            while let Some(payload) = self.queued.front() {
                if stream.buffered_amount().ok()? + payload.len() > MAX_BUFFERED {
                    break;
                }
                stream.write(now, payload).ok()?;
                self.queued.pop_front();
            }
        }
        while let Some(event) = association.poll_endpoint_event() {
            self.endpoint.handle_event(*handle, event);
        }
        while let Some(packet) = association.poll_transmit(now) {
            if let Payload::RawEncode(datagrams) = packet.message {
                outgoing.extend(datagrams.into_iter().map(|data| data.to_vec()));
            }
        }
        Some(outgoing)
    }
}
