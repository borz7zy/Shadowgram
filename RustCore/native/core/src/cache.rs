// SPDX-FileCopyrightText: borz7zy
// SPDX-License-Identifier: MIT

//! JNI surface for `com.github.borz7zy.rustcore.RustCache`.
//!
//! Buckets are opened by name ([`open`]) and addressed by the returned handle.
//! Each one is a write-through pair of a RAM LRU over an optional disk store
//! in `<root>/<name>`. A *durable* bucket never evicts from disk. Keys and
//! values are opaque bytes.

use std::path::PathBuf;
use std::sync::Mutex;

use jni::objects::{JByteArray, JClass, JLongArray, JString};
use jni::sys::{jboolean, jbyteArray, jint, jlong, jlongArray};
use jni::JNIEnv;

use rustcache::Bucket;

struct Registry {
    root: Option<PathBuf>,
    buckets: Vec<(String, &'static Mutex<Bucket>)>,
}

static REGISTRY: Mutex<Registry> = Mutex::new(Registry { root: None, buckets: Vec::new() });

pub(crate) fn bucket(h: jint) -> Option<&'static Mutex<Bucket>> {
    REGISTRY.lock().ok()?.buckets.get(h as usize).map(|(_, b)| *b)
}

fn all() -> Vec<&'static Mutex<Bucket>> {
    REGISTRY.lock().map(|r| r.buckets.iter().map(|(_, b)| *b).collect()).unwrap_or_default()
}

fn jbytes(env: &mut JNIEnv, arr: &JByteArray) -> Option<Vec<u8>> {
    env.convert_byte_array(arr).ok()
}

/// Point every bucket (opened now or later) at its directory under `root`.
#[no_mangle]
pub extern "system" fn Java_com_github_borz7zy_rustcore_RustCache_nativeInit<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    root: JString<'l>,
) {
    let Ok(root) = env.get_string(&root).map(String::from) else { return };
    let root = PathBuf::from(root);
    let _ = std::fs::create_dir_all(&root);
    let Ok(mut reg) = REGISTRY.lock() else { return };
    for (name, b) in &reg.buckets {
        if let Ok(mut g) = b.lock() {
            g.install_disk(root.join(name));
        }
    }
    reg.root = Some(root);
}

/// Open (or re-open — same name returns the same handle) a bucket.
#[no_mangle]
pub extern "system" fn Java_com_github_borz7zy_rustcore_RustCache_nativeOpen<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    name: JString<'l>,
    mem_bytes: jlong,
    disk_bytes: jlong,
    durable: jboolean,
) -> jint {
    let Ok(name) = env.get_string(&name).map(String::from) else { return -1 };
    open(name, mem_bytes, disk_bytes, durable != 0)
}

/// Open (or re-open — same name returns the same handle) a bucket; -1 on a
/// poisoned registry. Also used from Rust for internal buckets.
pub(crate) fn open(name: String, mem_bytes: jlong, disk_bytes: jlong, durable: bool) -> jint {
    let Ok(mut reg) = REGISTRY.lock() else { return -1 };
    if let Some(i) = reg.buckets.iter().position(|(n, _)| *n == name) {
        return i as jint;
    }
    let disk_cap = if durable { u64::MAX } else { disk_bytes.max(0) as u64 };
    let mut b = Bucket::new(mem_bytes.max(0) as usize, disk_cap);
    if let Some(root) = &reg.root {
        b.install_disk(root.join(&name));
    }
    // Buckets live for the whole process — leaking gives `'static` refs
    // without holding the registry lock during I/O.
    reg.buckets.push((name, Box::leak(Box::new(Mutex::new(b)))));
    (reg.buckets.len() - 1) as jint
}

#[no_mangle]
pub extern "system" fn Java_com_github_borz7zy_rustcore_RustCache_nativePut<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    h: jint,
    key: JByteArray<'l>,
    value: JByteArray<'l>,
) {
    let (Some(b), Some(k), Some(v)) = (bucket(h), jbytes(&mut env, &key), jbytes(&mut env, &value)) else { return };
    if let Ok(mut g) = b.lock() {
        g.put(k, v);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_github_borz7zy_rustcore_RustCache_nativeGet<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    h: jint,
    key: JByteArray<'l>,
) -> jbyteArray {
    let (Some(b), Some(k)) = (bucket(h), jbytes(&mut env, &key)) else { return std::ptr::null_mut() };
    let Some(v) = b.lock().ok().and_then(|mut g| g.get(&k)) else { return std::ptr::null_mut() };
    env.byte_array_from_slice(&v).map(|a| a.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_github_borz7zy_rustcore_RustCache_nativeRemove<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    h: jint,
    key: JByteArray<'l>,
) {
    let (Some(b), Some(k)) = (bucket(h), jbytes(&mut env, &key)) else { return };
    if let Ok(mut g) = b.lock() {
        g.remove(&k);
    }
}

/// Drop one bucket — RAM and disk.
#[no_mangle]
pub extern "system" fn Java_com_github_borz7zy_rustcore_RustCache_nativeClear<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    h: jint,
) {
    if let Some(Ok(mut g)) = bucket(h).map(|b| b.lock()) {
        g.clear();
    }
}

/// Drop everything in every bucket — RAM and disk. Logout / format change.
#[no_mangle]
pub extern "system" fn Java_com_github_borz7zy_rustcore_RustCache_nativeClearAll<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
) {
    for b in all() {
        if let Ok(mut g) = b.lock() {
            g.clear();
        }
    }
}

/// Drop only the RAM tier — the right call from `onTrimMemory`.
#[no_mangle]
pub extern "system" fn Java_com_github_borz7zy_rustcore_RustCache_nativeTrimMemory<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
) {
    for b in all() {
        if let Ok(mut g) = b.lock() {
            g.clear_mem_only();
        }
    }
}

/// `[mem, disk]` byte usage per bucket, in handle order.
#[no_mangle]
pub extern "system" fn Java_com_github_borz7zy_rustcore_RustCache_nativeStats<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
) -> jlongArray {
    let out: Vec<jlong> = all()
        .into_iter()
        .flat_map(|b| {
            let (m, d) = b.lock().map(|g| (g.used_mem_bytes() as jlong, g.used_disk_bytes() as jlong)).unwrap_or((0, 0));
            [m, d]
        })
        .collect();
    let Ok(arr) = env.new_long_array(out.len() as i32) else { return std::ptr::null_mut() };
    if env.set_long_array_region(&arr, 0, &out).is_err() {
        return std::ptr::null_mut();
    }
    JLongArray::into_raw(arr)
}
