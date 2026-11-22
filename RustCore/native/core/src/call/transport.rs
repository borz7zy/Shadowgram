// SPDX-FileCopyrightText: borz7zy
// SPDX-License-Identifier: MIT

use super::audio::{Capture, Pcm, Playout};
use super::signaling::{Protocol, Wire};
use rtc::ice::mdns::MulticastDnsMode;
use rtc::media_stream::MediaStreamTrack;
use rtc::rtp::packet::Packet;
use rtc::rtp_transceiver::rtp_sender::{
    RTCRtpCodec, RTCRtpCodecParameters, RTCRtpCodingParameters, RTCRtpEncodingParameters,
    RtpCodecKind,
};
use serde::{Deserialize, Serialize};
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::{mpsc as blocking, Arc};
use std::time::{Duration, Instant};
use tokio::sync::{mpsc, watch};
use tokio::time::{self, MissedTickBehavior};
use webrtc::data_channel::{DataChannel, DataChannelEvent};
use webrtc::media_stream::track_local::static_rtp::TrackLocalStaticRTP;
use webrtc::media_stream::track_local::TrackLocal;
use webrtc::media_stream::track_remote::{TrackRemote, TrackRemoteEvent};
use webrtc::peer_connection::{
    register_default_interceptors, MediaEngine, PeerConnection, PeerConnectionBuilder,
    PeerConnectionEventHandler, RTCBundlePolicy, RTCConfigurationBuilder, RTCIceCandidateInit,
    RTCIceServer, RTCIceTransportPolicy, RTCPeerConnectionIceEvent, RTCPeerConnectionState,
    RTCRtcpMuxPolicy, RTCSessionDescription, Registry, SettingEngineBuilder,
};
use webrtc::runtime::{Runtime, TokioRuntime};

const MAX_CANDIDATES: usize = 96;

fn checked<T, E: std::fmt::Debug>(result: Result<T, E>, _stage: &str) -> Option<T> {
    #[cfg(test)]
    if let Err(error) = &result {
        eprintln!("call {_stage}: {error:?}");
    }
    result.ok()
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct Config {
    pub outgoing: bool,
    pub allow_p2p: bool,
    pub protocol: Protocol,
    pub servers: Vec<Server>,
}

#[derive(Deserialize)]
pub(super) struct Server {
    host: String,
    port: u16,
    turn: bool,
    stun: bool,
    username: String,
    password: String,
}

impl Config {
    fn ice_servers(self) -> Vec<RTCIceServer> {
        self.servers
            .into_iter()
            .take(64)
            .filter_map(|server| {
                let host = server.host.trim();
                if server.port == 0 || host.is_empty() || host.len() > 253 {
                    return None;
                }
                let authority = match host.parse::<IpAddr>() {
                    Ok(address) => SocketAddr::new(address, server.port).to_string(),
                    Err(_)
                        if host
                            .bytes()
                            .all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'-') =>
                    {
                        format!("{host}:{}", server.port)
                    }
                    Err(_) => return None,
                };
                let mut urls = Vec::new();
                if self.allow_p2p && server.stun {
                    urls.push(format!("stun:{authority}"));
                }
                if server.turn
                    && server.username != "reflector"
                    && !server.username.is_empty()
                    && !server.password.is_empty()
                {
                    urls.push(format!("turn:{authority}?transport=udp"));
                }
                (!urls.is_empty()).then_some(RTCIceServer {
                    urls,
                    username: server.username,
                    credential: server.password,
                })
            })
            .collect()
    }
}

#[derive(Debug)]
pub(super) enum Event {
    Signal(Vec<u8>),
    Connecting,
    Connected,
    Failed,
    RemoteMute(bool),
}

pub(super) struct Io {
    pub signals: mpsc::Receiver<Vec<u8>>,
    pub microphone: mpsc::Receiver<Pcm>,
    pub events: blocking::SyncSender<Event>,
    pub speaker: blocking::SyncSender<Pcm>,
    pub mute: watch::Receiver<bool>,
    pub stop: watch::Receiver<bool>,
}

enum Local {
    Candidate(String),
    State(RTCPeerConnectionState),
    DataOpen(Arc<dyn DataChannel>),
    RemoteMute(bool),
}

struct Handler {
    tx: mpsc::Sender<Local>,
    incoming_audio: mpsc::Sender<Packet>,
    executor: tokio::runtime::Handle,
}

#[async_trait::async_trait]
impl PeerConnectionEventHandler for Handler {
    async fn on_ice_candidate(&self, event: RTCPeerConnectionIceEvent) {
        if let Ok(candidate) = event.candidate.to_json() {
            let _ = self.tx.try_send(Local::Candidate(candidate.candidate));
        }
    }

    async fn on_connection_state_change(&self, state: RTCPeerConnectionState) {
        let _ = self.tx.try_send(Local::State(state));
    }

    async fn on_track(&self, track: Arc<dyn TrackRemote>) {
        if track.kind().await != RtpCodecKind::Audio {
            return;
        }
        let tx = self.incoming_audio.clone();
        self.executor.spawn(async move {
            while let Some(event) = track.poll().await {
                match event {
                    TrackRemoteEvent::OnRtpPacket(packet) => {
                        let _ = tx.try_send(packet);
                    }
                    TrackRemoteEvent::OnEnded => break,
                    _ => {}
                }
            }
        });
    }

    async fn on_data_channel(&self, channel: Arc<dyn DataChannel>) {
        self.monitor_channel(channel);
    }
}

impl Handler {
    fn monitor_channel(&self, channel: Arc<dyn DataChannel>) {
        let tx = self.tx.clone();
        self.executor.spawn(async move {
            while let Some(event) = channel.poll().await {
                match event {
                    DataChannelEvent::OnOpen => {
                        let _ = tx.try_send(Local::DataOpen(channel.clone()));
                    }
                    DataChannelEvent::OnMessage(message) if message.data.len() <= 4096 => {
                        if let Ok(Message::MediaState { muted, .. }) =
                            serde_json::from_slice(&message.data)
                        {
                            let _ = tx.try_send(Local::RemoteMute(muted));
                        }
                    }
                    DataChannelEvent::OnClose => break,
                    _ => {}
                }
            }
        });
    }
}

#[derive(Serialize, Deserialize)]
#[serde(tag = "@type")]
enum Message {
    #[serde(rename = "offer")]
    Offer { sdp: String },
    #[serde(rename = "answer")]
    Answer { sdp: String },
    #[serde(rename = "candidate")]
    Candidate {
        sdp: String,
        mid: String,
        mline: u16,
    },
    #[serde(rename = "MediaState", rename_all = "camelCase")]
    MediaState {
        muted: bool,
        #[serde(default)]
        low_battery: bool,
        #[serde(default)]
        video_state: String,
        #[serde(default)]
        video_rotation: i32,
        #[serde(default)]
        screencast_state: String,
    },
}

struct Negotiation {
    outgoing: bool,
    remote_ready: bool,
    local_mid: String,
    pending_remote: Vec<RTCIceCandidateInit>,
    pending_local: Vec<String>,
}

impl Negotiation {
    fn new(outgoing: bool) -> Self {
        Self {
            outgoing,
            remote_ready: false,
            local_mid: String::new(),
            pending_remote: Vec::new(),
            pending_local: Vec::new(),
        }
    }

    async fn local_description(
        &mut self,
        pc: &dyn PeerConnection,
        offer: bool,
    ) -> webrtc::error::Result<Message> {
        let description = if offer {
            pc.create_offer(None).await?
        } else {
            pc.create_answer(None).await?
        };
        let sdp = description.sdp.clone();
        self.local_mid = sdp
            .lines()
            .find_map(|line| line.strip_prefix("a=mid:"))
            .unwrap_or("0")
            .to_owned();
        pc.set_local_description(description).await?;
        Ok(if offer {
            Message::Offer { sdp }
        } else {
            Message::Answer { sdp }
        })
    }

    async fn incoming(
        &mut self,
        pc: &dyn PeerConnection,
        message: Message,
    ) -> webrtc::error::Result<Option<Message>> {
        match message {
            Message::Offer { sdp } if !self.outgoing || self.remote_ready => {
                pc.set_remote_description(RTCSessionDescription::offer(sdp)?)
                    .await?;
                let answer = self.local_description(pc, false).await?;
                self.finish_remote(pc).await;
                Ok(Some(answer))
            }
            Message::Answer { sdp } if self.outgoing && !self.remote_ready => {
                pc.set_remote_description(RTCSessionDescription::answer(sdp)?)
                    .await?;
                self.finish_remote(pc).await;
                Ok(None)
            }
            Message::Candidate { sdp, mid, mline } if sdp.len() <= 2048 && mid.len() <= 64 => {
                let init = RTCIceCandidateInit {
                    candidate: sdp.trim_start_matches("a=").to_owned(),
                    sdp_mid: Some(mid),
                    sdp_mline_index: Some(mline),
                    ..Default::default()
                };
                if self.remote_ready {
                    let _ = pc.add_ice_candidate(init).await;
                } else if self.pending_remote.len() < MAX_CANDIDATES {
                    self.pending_remote.push(init);
                }
                Ok(None)
            }
            _ => Ok(None),
        }
    }

    async fn finish_remote(&mut self, pc: &dyn PeerConnection) {
        self.remote_ready = true;
        for candidate in self.pending_remote.drain(..) {
            let _ = pc.add_ice_candidate(candidate).await;
        }
    }

    fn candidate(&self, sdp: String) -> Message {
        Message::Candidate {
            sdp,
            mid: self.local_mid.clone(),
            mline: 0,
        }
    }
}

fn emit(signaling: &mut Wire, out: &blocking::SyncSender<Event>, message: &Message) -> Option<()> {
    let bytes = serde_json::to_vec(message).ok()?;
    send_packets(out, signaling.send(&bytes, Instant::now())?)
}

fn send_packets(out: &blocking::SyncSender<Event>, packets: Vec<Vec<u8>>) -> Option<()> {
    for packet in packets {
        out.try_send(Event::Signal(packet)).ok()?;
    }
    Some(())
}

async fn inform_mute(channel: &dyn DataChannel, muted: bool) {
    let message = Message::MediaState {
        muted,
        low_battery: false,
        video_state: "inactive".into(),
        video_rotation: 0,
        screencast_state: "inactive".into(),
    };
    if let Ok(json) = serde_json::to_string(&message) {
        let _ = channel.send_text(&json).await;
    }
}

pub(super) async fn run(config: Config, signaling: Wire, io: Io) -> Option<()> {
    let addresses = vec![
        SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0)),
        SocketAddr::from((Ipv6Addr::UNSPECIFIED, 0)),
    ];
    run_on(config, signaling, io, Arc::new(TokioRuntime), addresses).await
}

fn opus_media() -> Option<(RTCRtpCodec, MediaEngine, Registry)> {
    let codec = RTCRtpCodec {
        mime_type: "audio/opus".into(),
        clock_rate: 48_000,
        channels: 2,
        sdp_fmtp_line: "minptime=10;useinbandfec=0".into(),
        ..Default::default()
    };
    let mut media = MediaEngine::default();
    checked(
        media.register_codec(
            RTCRtpCodecParameters {
                rtp_codec: codec.clone(),
                payload_type: 111,
                ..Default::default()
            },
            RtpCodecKind::Audio,
        ),
        "codec",
    )?;
    let registry = checked(
        register_default_interceptors(Registry::new(), &mut media),
        "interceptors",
    )?;
    Some((codec, media, registry))
}

fn opus_track(codec: RTCRtpCodec, ssrc: u32) -> MediaStreamTrack {
    MediaStreamTrack::new(
        "0".to_owned(),
        "0".to_owned(),
        "Voice".to_owned(),
        RtpCodecKind::Audio,
        vec![RTCRtpEncodingParameters {
            rtp_coding_parameters: RTCRtpCodingParameters {
                ssrc: Some(ssrc),
                ..Default::default()
            },
            codec,
            ..Default::default()
        }],
    )
}

async fn run_on(
    config: Config,
    mut signaling: Wire,
    mut io: Io,
    runtime: Arc<dyn Runtime>,
    bind_addresses: Vec<SocketAddr>,
) -> Option<()> {
    let outgoing = config.outgoing;
    let allow_p2p = config.allow_p2p;
    let servers = config.ice_servers();
    if !allow_p2p
        && !servers
            .iter()
            .any(|server| server.urls.iter().any(|url| url.starts_with("turn:")))
    {
        return None;
    }
    let mut capture = Capture::new()?;
    let mut playout = Playout::new()?;
    let (local_tx, mut local_rx) = mpsc::channel(128);
    let (audio_tx, mut audio_rx) = mpsc::channel(24);
    let handler = Arc::new(Handler {
        tx: local_tx,
        incoming_audio: audio_tx,
        executor: tokio::runtime::Handle::current(),
    });
    let (codec, media, registry) = opus_media()?;
    let config = RTCConfigurationBuilder::default()
        .with_ice_servers(servers)
        .with_ice_transport_policy(if allow_p2p {
            RTCIceTransportPolicy::All
        } else {
            RTCIceTransportPolicy::Relay
        })
        .with_bundle_policy(RTCBundlePolicy::MaxBundle)
        .with_rtcp_mux_policy(RTCRtcpMuxPolicy::Require)
        .build();
    let settings = SettingEngineBuilder::default()
        .with_multicast_dns_mode(MulticastDnsMode::Disabled)
        .build();
    let pc = checked(
        PeerConnectionBuilder::new()
            .with_configuration(config)
            .with_media_engine(media)
            .with_interceptor_registry(registry)
            .with_setting_engine(settings)
            .with_runtime(runtime)
            .with_handler(handler.clone())
            .with_udp_addrs(bind_addresses)
            .build()
            .await,
        "peer creation",
    )?;
    let track = Arc::new(TrackLocalStaticRTP::new(opus_track(codec, capture.ssrc)));
    let result = async {
        checked(pc.add_track(track.clone()).await, "add audio track")?;
        let mut negotiation = Negotiation::new(outgoing);
        if outgoing {
            if let Ok(channel) = pc.create_data_channel("data", None).await {
                handler.monitor_channel(channel);
            }
            let offer = checked(negotiation.local_description(&pc, true).await, "local offer")?;
            emit(&mut signaling, &io.events, &offer)?;
        }
        let mut channel: Option<Arc<dyn DataChannel>> = None;
        let mut connected = false;
        let mut audio_tick = time::interval(Duration::from_millis(20));
        audio_tick.set_missed_tick_behavior(MissedTickBehavior::Skip);
        let mut retry_tick = time::interval(Duration::from_millis(60));
        retry_tick.set_missed_tick_behavior(MissedTickBehavior::Skip);
        loop {
            tokio::select! {
                _ = io.stop.changed() => break Some(()),
                signal = io.signals.recv() => {
                    let Some(signal) = signal else { break Some(()) };
                    let Some((messages, outgoing)) = signaling.receive(signal, Instant::now()) else { continue };
                    send_packets(&io.events, outgoing)?;
                    for payload in messages {
                        let Ok(message) = serde_json::from_slice(&payload) else { continue };
                        if let Message::MediaState { muted, .. } = message {
                            let _ = io.events.try_send(Event::RemoteMute(muted));
                            continue;
                        }
                        if let Some(answer) = checked(negotiation.incoming(&pc, message).await, "remote description")? {
                            emit(&mut signaling, &io.events, &answer)?;
                            for candidate in std::mem::take(&mut negotiation.pending_local) {
                                emit(&mut signaling, &io.events, &negotiation.candidate(candidate))?;
                            }
                        }
                    }
                }
                Some(event) = local_rx.recv() => match event {
                    Local::Candidate(candidate) if negotiation.local_mid.is_empty() => {
                        if negotiation.pending_local.len() < MAX_CANDIDATES {
                            negotiation.pending_local.push(candidate);
                        }
                    }
                    Local::Candidate(candidate) => {
                        emit(&mut signaling, &io.events, &negotiation.candidate(candidate))?;
                    }
                    Local::State(RTCPeerConnectionState::Connected) => {
                        connected = true;
                        io.events.try_send(Event::Connected).ok()?;
                    }
                    Local::State(RTCPeerConnectionState::Disconnected | RTCPeerConnectionState::Connecting) => {
                        connected = false;
                        io.events.try_send(Event::Connecting).ok()?;
                    }
                    Local::State(RTCPeerConnectionState::Closed | RTCPeerConnectionState::Failed) => break None,
                    Local::State(_) => {}
                    Local::RemoteMute(muted) => { let _ = io.events.try_send(Event::RemoteMute(muted)); }
                    Local::DataOpen(data) => {
                        let muted = *io.mute.borrow();
                        inform_mute(&*data, muted).await;
                        channel = Some(data);
                    }
                },
                _ = io.mute.changed() => {
                    if let Some(data) = &channel {
                        let muted = *io.mute.borrow();
                        inform_mute(&**data, muted).await;
                    }
                }
                Some(mut frame) = io.microphone.recv() => {
                    if connected {
                        if *io.mute.borrow() { frame.fill(0); }
                        if let Some(packet) = capture.encode(&frame) { let _ = track.write_rtp(packet).await; }
                    }
                }
                Some(packet) = audio_rx.recv() => { playout.push(packet); }
                _ = audio_tick.tick(), if connected => { let _ = io.speaker.try_send(playout.frame()); }
                _ = retry_tick.tick() => {
                    send_packets(&io.events, signaling.tick(Instant::now())?)?;
                }
            }
        }
    }.await;
    let _ = time::timeout(Duration::from_secs(1), pc.close()).await;
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    use webrtc::runtime::mock::{MockRuntime, MockUDPNetwork};
    use zeroize::Zeroizing;

    #[test]
    fn web_rtc_uses_encrypted_rtp_for_opus() {
        use rtc::peer_connection::message::RTCMessage;
        use rtc::peer_connection::transport::{
            CandidateConfig, CandidateHostConfig, RTCIceCandidate,
        };
        use rtc::peer_connection::{RTCPeerConnection, RTCPeerConnectionBuilder};
        use rtc::sansio::Protocol as _;
        use rtc::shared::{TransportContext, TransportProtocol};

        fn core(
            now: Instant,
            capture: &Capture,
        ) -> (RTCPeerConnection, rtc::rtp_transceiver::RTCRtpSenderId) {
            let (codec, engine, registry) = opus_media().unwrap();
            let mut pc = RTCPeerConnectionBuilder::new()
                .with_media_engine(engine)
                .with_interceptor_registry(registry)
                .build(now)
                .unwrap();
            let sender = pc.add_track(opus_track(codec, capture.ssrc)).unwrap();
            (pc, sender)
        }

        fn candidate(addr: SocketAddr) -> RTCIceCandidateInit {
            let host = CandidateHostConfig {
                base_config: CandidateConfig {
                    network: "udp".into(),
                    address: addr.ip().to_string(),
                    port: addr.port(),
                    component: 1,
                    ..Default::default()
                },
                ..Default::default()
            }
            .new_candidate_host()
            .unwrap();
            RTCIceCandidate::from(&host).to_json().unwrap()
        }

        fn forward_media(from: &mut RTCPeerConnection, to: &mut RTCPeerConnection, now: Instant) {
            while let Some(packet) = from.poll_write() {
                to.handle_read(rtc::shared::TaggedBytesMut {
                    now,
                    transport: TransportContext {
                        local_addr: packet.transport.peer_addr,
                        peer_addr: packet.transport.local_addr,
                        ecn: None,
                        transport_protocol: TransportProtocol::UDP,
                    },
                    message: packet.message,
                })
                .unwrap();
            }
        }

        let start = Instant::now();
        let mut left_capture = Capture::new().unwrap();
        let right_capture = Capture::new().unwrap();
        let (mut left, sender) = core(start, &left_capture);
        let (mut right, _) = core(start, &right_capture);
        let left_address = SocketAddr::from(([192, 0, 2, 1], 5000));
        let right_address = SocketAddr::from(([192, 0, 2, 2], 5000));
        let offer = left.create_offer(None).unwrap();
        left.set_local_description(start, offer.clone()).unwrap();
        right.set_remote_description(start, offer).unwrap();
        let answer = right.create_answer(None).unwrap();
        right.set_local_description(start, answer.clone()).unwrap();
        left.set_remote_description(start, answer).unwrap();
        left.add_local_candidate(candidate(left_address)).unwrap();
        right.add_local_candidate(candidate(right_address)).unwrap();
        left.add_local_candidate(RTCIceCandidateInit::default())
            .unwrap();
        right
            .add_local_candidate(RTCIceCandidateInit::default())
            .unwrap();
        left.add_remote_candidate(candidate(right_address)).unwrap();
        right.add_remote_candidate(candidate(left_address)).unwrap();
        let frame = std::array::from_fn(|i| ((i as f32 * 0.09).sin() * 10_000.0) as i16);
        let audio = left_capture.encode(&frame).unwrap();
        let mut last_state = None;
        let mut last_send = None;
        for i in 1..1000 {
            let now = start + Duration::from_millis(20 * i);
            if left.poll_timeout().is_some_and(|deadline| deadline <= now) {
                left.handle_timeout(now).unwrap();
            }
            if right.poll_timeout().is_some_and(|deadline| deadline <= now) {
                right.handle_timeout(now).unwrap();
            }
            forward_media(&mut left, &mut right, now);
            forward_media(&mut right, &mut left, now);
            while let Some(event) = left.poll_event() {
                if let rtc::peer_connection::event::RTCPeerConnectionEvent::OnConnectionStateChangeEvent(state) = event {
                    last_state = Some(state);
                }
            }
            while right.poll_event().is_some() {}
            // The sender can write only after DTLS has established the SRTP context.
            if let Some(mut sender) = left.rtp_sender(sender) {
                last_send = sender.write_rtp(now, audio.clone()).err();
            }
            while let Some(message) = right.poll_read() {
                if let RTCMessage::RtpPacket(_, packet) = message.message {
                    assert_eq!(packet.payload, audio.payload);
                    return;
                }
            }
        }
        panic!("the receiving peer did not get the Opus packet through DTLS/SRTP: {last_state:?}, {last_send:?}");
    }

    struct Peer {
        signals: mpsc::Sender<Vec<u8>>,
        mic: mpsc::Sender<Pcm>,
        speaker: blocking::Receiver<Pcm>,
        events: blocking::Receiver<Event>,
        stop: watch::Sender<bool>,
        mute: watch::Sender<bool>,
        task: tokio::task::JoinHandle<Option<()>>,
        connected: bool,
        peer_muted: bool,
    }

    fn peer(outgoing: bool, runtime: Arc<MockRuntime>) -> Peer {
        let (signals, signal_rx) = mpsc::channel(128);
        let (mic, mic_rx) = mpsc::channel(5);
        let (event_tx, events) = blocking::sync_channel(256);
        let (speaker_tx, speaker) = blocking::sync_channel(5);
        let (mute, muted) = watch::channel(false);
        let (stop, stopped) = watch::channel(false);
        let io = Io {
            signals: signal_rx,
            microphone: mic_rx,
            events: event_tx,
            speaker: speaker_tx,
            mute: muted,
            stop: stopped,
        };
        let config = Config {
            outgoing,
            allow_p2p: true,
            protocol: Protocol::Sctp,
            servers: Vec::new(),
        };
        let key = Zeroizing::new(std::array::from_fn(|i| i as u8));
        let address = SocketAddr::from((Ipv4Addr::LOCALHOST, 0));
        let wire = Wire::new(key, outgoing, config.protocol).unwrap();
        let task = tokio::spawn(run_on(config, wire, io, runtime, vec![address]));
        Peer {
            signals,
            mic,
            speaker,
            events,
            mute,
            stop,
            task,
            connected: false,
            peer_muted: false,
        }
    }

    fn forward(from: &mut Peer, to: &Peer) {
        for event in from.events.try_iter() {
            match event {
                Event::Signal(bytes) => to.signals.try_send(bytes).unwrap(),
                Event::Connected => from.connected = true,
                Event::RemoteMute(value) => from.peer_muted = value,
                Event::Failed => panic!("voice call failed"),
                Event::Connecting => {}
            }
        }
    }

    #[tokio::test]
    #[ignore = "requires permission to bind loopback UDP sockets"]
    async fn encrypted_signaling_negotiates_two_way_opus_and_mute() {
        let network = Arc::new(MockUDPNetwork::default());
        let left = Arc::new(MockRuntime::with_network(network.clone()));
        let right = Arc::new(MockRuntime::with_network(network));
        let mut a = peer(true, left.clone());
        let mut b = peer(false, right.clone());
        let tick = || {
            left.clock().advance(Duration::from_millis(20));
            right.clock().advance(Duration::from_millis(20));
        };
        for _ in 0..350 {
            forward(&mut a, &b);
            forward(&mut b, &a);
            if a.connected && b.connected {
                break;
            }
            tick();
            time::sleep(Duration::from_millis(2)).await;
        }
        assert!(
            a.connected && b.connected,
            "both peers should complete ICE and DTLS"
        );

        let pcm = std::array::from_fn(|i| ((i as f32 * 0.065).sin() * 12000.0) as i16);
        let mut audio_at_a = false;
        let mut audio_at_b = false;
        for _ in 0..25 {
            let _ = a.mic.try_send(pcm);
            let _ = b.mic.try_send(pcm);
            tick();
            time::sleep(Duration::from_millis(20)).await;
            audio_at_a |= a
                .speaker
                .try_iter()
                .flatten()
                .any(|value| value.unsigned_abs() > 600);
            audio_at_b |= b
                .speaker
                .try_iter()
                .flatten()
                .any(|value| value.unsigned_abs() > 600);
            if audio_at_a && audio_at_b {
                break;
            }
        }
        assert!(
            audio_at_a && audio_at_b,
            "both peers should decode remote Opus"
        );

        a.mute.send(true).unwrap();
        for _ in 0..100 {
            forward(&mut a, &b);
            forward(&mut b, &a);
            if b.peer_muted {
                break;
            }
            tick();
            time::sleep(Duration::from_millis(2)).await;
        }
        assert!(
            b.peer_muted,
            "remote mute indication should arrive over SCTP"
        );
        a.stop.send(true).unwrap();
        b.stop.send(true).unwrap();
        assert!(a.task.await.unwrap().is_some());
        assert!(b.task.await.unwrap().is_some());
    }
}
