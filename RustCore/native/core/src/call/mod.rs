// SPDX-FileCopyrightText: borz7zy
// SPDX-License-Identifier: MIT

mod audio;
mod sctp;
mod signaling;
mod transport;

use jni::objects::{JByteArray, JObject, JShortArray, JString};
use jni::sys::{jboolean, jbyteArray, jlong, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use std::collections::HashMap;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{mpsc as blocking, Arc, LazyLock, Mutex};
use std::time::Duration;
use tokio::sync::{mpsc, watch};
use zeroize::Zeroizing;

use audio::{Pcm, FRAME};
use signaling::{Wire, MAX_INCOMING};
use transport::{Config, Event, Io};

const MAX_CONFIG: i32 = 64 * 1024;
static NEXT_HANDLE: AtomicI64 = AtomicI64::new(1);
static CALLS: LazyLock<Mutex<HashMap<jlong, Arc<Call>>>> = LazyLock::new(Mutex::default);

struct Call {
    signal: mpsc::Sender<Vec<u8>>,
    microphone: mpsc::Sender<Pcm>,
    events: Mutex<blocking::Receiver<Event>>,
    speaker: Mutex<blocking::Receiver<Pcm>>,
    mute: watch::Sender<bool>,
    stop: watch::Sender<bool>,
}

impl Drop for Call {
    fn drop(&mut self) {
        let _ = self.stop.send(true);
    }
}

fn start(config: Config, key: Zeroizing<[u8; 256]>) -> Option<jlong> {
    let signaling = Wire::new(key, config.outgoing, config.protocol)?;
    let (signal, signals) = mpsc::channel(128);
    let (microphone, capture) = mpsc::channel(5);
    let (notify, events) = blocking::sync_channel(256);
    let (output, speaker) = blocking::sync_channel(5);
    let (mute, muted) = watch::channel(false);
    let (stop, stopped) = watch::channel(false);
    let io = Io {
        signals,
        microphone: capture,
        events: notify.clone(),
        speaker: output,
        mute: muted,
        stop: stopped,
    };
    let call = Arc::new(Call {
        signal,
        microphone,
        events: Mutex::new(events),
        speaker: Mutex::new(speaker),
        mute,
        stop,
    });
    std::thread::Builder::new()
        .name("shadowgram-call".into())
        .spawn(move || {
            let result = tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
                .ok()
                .and_then(|runtime| runtime.block_on(transport::run(config, signaling, io)));
            if result.is_none() {
                let _ = notify.try_send(Event::Failed);
            }
        })
        .ok()?;
    let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
    CALLS.lock().ok()?.insert(handle, call);
    Some(handle)
}

fn call(handle: jlong) -> Option<Arc<Call>> {
    CALLS.lock().ok()?.get(&handle).cloned()
}

#[no_mangle]
pub extern "system" fn Java_com_github_borz7zy_rustcore_NativeCall_nativeOpen(
    mut env: JNIEnv,
    _: JObject,
    raw_key: JByteArray,
    json: JString,
) -> jlong {
    let mut read = || -> Option<jlong> {
        if env.get_array_length(&raw_key).ok()? != 256 {
            return None;
        }
        let raw = Zeroizing::new(env.convert_byte_array(&raw_key).ok()?);
        let mut key = Zeroizing::new([0; 256]);
        key.copy_from_slice(&raw);
        let json = env.get_string(&json).ok()?.to_string_lossy().into_owned();
        if json.len() > MAX_CONFIG as usize {
            return None;
        }
        let config = serde_json::from_str(&json).ok()?;
        start(config, key)
    };
    read().unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_github_borz7zy_rustcore_NativeCall_nativeSignal(
    env: JNIEnv,
    _: JObject,
    handle: jlong,
    packet: JByteArray,
) -> jboolean {
    let accepted = (|| {
        let call = call(handle)?;
        if !(16..=MAX_INCOMING).contains(&(env.get_array_length(&packet).ok()? as usize)) {
            return None;
        }
        call.signal
            .try_send(env.convert_byte_array(&packet).ok()?)
            .ok()
    })();
    if accepted.is_some() {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_com_github_borz7zy_rustcore_NativeCall_nativePoll(
    env: JNIEnv,
    _: JObject,
    handle: jlong,
) -> jbyteArray {
    let poll = || -> Option<jbyteArray> {
        let call = call(handle)?;
        let event = call
            .events
            .lock()
            .ok()?
            .recv_timeout(Duration::from_millis(120));
        let bytes = match event {
            Ok(Event::Signal(mut bytes)) => {
                bytes.insert(0, 1);
                bytes
            }
            Ok(Event::Connecting) => vec![2],
            Ok(Event::Connected) => vec![3],
            Ok(Event::Failed) | Err(blocking::RecvTimeoutError::Disconnected) => vec![4],
            Ok(Event::RemoteMute(muted)) => vec![5, u8::from(muted)],
            Err(blocking::RecvTimeoutError::Timeout) => return None,
        };
        Some(env.byte_array_from_slice(&bytes).ok()?.into_raw())
    };
    poll().unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_github_borz7zy_rustcore_NativeCall_nativeCapture(
    env: JNIEnv,
    _: JObject,
    handle: jlong,
    samples: JShortArray,
) {
    let Some(call) = call(handle) else { return };
    if !matches!(env.get_array_length(&samples), Ok(n) if n == FRAME as i32) {
        return;
    }
    let mut frame = [0; FRAME];
    if env.get_short_array_region(&samples, 0, &mut frame).is_ok() {
        let _ = call.microphone.try_send(frame);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_github_borz7zy_rustcore_NativeCall_nativePlayback(
    env: JNIEnv,
    _: JObject,
    handle: jlong,
    samples: JShortArray,
) -> jboolean {
    let read = || {
        let call = call(handle)?;
        if env.get_array_length(&samples).ok()? != FRAME as i32 {
            return None;
        }
        let pcm = call
            .speaker
            .lock()
            .ok()?
            .recv_timeout(Duration::from_millis(30))
            .ok()?;
        env.set_short_array_region(&samples, 0, &pcm).ok()
    };
    if read().is_some() {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_com_github_borz7zy_rustcore_NativeCall_nativeMute(
    _: JNIEnv,
    _: JObject,
    handle: jlong,
    muted: jboolean,
) {
    if let Some(call) = call(handle) {
        let _ = call.mute.send(muted != JNI_FALSE);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_github_borz7zy_rustcore_NativeCall_nativeClose(
    _: JNIEnv,
    _: JObject,
    handle: jlong,
) {
    if let Some(call) = CALLS
        .lock()
        .ok()
        .and_then(|mut calls| calls.remove(&handle))
    {
        let _ = call.stop.send(true);
    }
}
