// SPDX-FileCopyrightText: borz7zy
// SPDX-License-Identifier: MIT

mod dsp;
mod pattern;
mod storage;

use dsp::Analysis;
use jni::{
    errors::{Error, Result},
    objects::{JByteArray, JByteBuffer, JClass, JObject, JString},
    sys::{jboolean, jbyteArray, jfloat, jint, jintArray, jlong, jobjectArray},
    JNIEnv,
};
use std::{
    panic::{catch_unwind, AssertUnwindSafe},
    ptr::null_mut,
    sync::{atomic::{AtomicI64, Ordering}, Mutex},
};
use storage::{Db, Store};

static NEXT: AtomicI64 = AtomicI64::new(1);
// The Android worker claims one decoder at a time. This additionally bounds native handles.
static ACTIVE: Mutex<Option<(i64, Analysis)>> = Mutex::new(None);

fn guarded<'local, T>(mut env: JNIEnv<'local>, default: T, run: impl FnOnce(&mut JNIEnv<'local>) -> Result<T>) -> T {
    match catch_unwind(AssertUnwindSafe(|| run(&mut env))) {
        Ok(Ok(value)) => value,
        Ok(Err(error)) => {
            if !env.exception_check().unwrap_or(true) {
                let _ = env.throw_new("java/lang/IllegalStateException", format!("Haptics: {error}"));
            }
            default
        }
        Err(_) => {
            if !env.exception_check().unwrap_or(true) {
                let _ = env.throw_new("java/lang/IllegalStateException", "Haptics native failure");
            }
            default
        }
    }
}

fn source(env: &mut JNIEnv<'_>, key: &JString<'_>, revision: &JString<'_>) -> Result<(String, String)> {
    let key: String = env.get_string(key)?.into();
    let revision: String = env.get_string(revision)?.into();
    if key.len() > 4096 || revision.len() > 1024 {
        return Err(Error::NullPtr("invalid haptics source"));
    }
    Ok((key, revision))
}

#[no_mangle]
extern "system" fn Java_com_github_borz7zy_rustcore_RustHaptics_nativeVersion(
    _env: JNIEnv<'_>, _class: JClass<'_>,
) -> jint {
    (i32::from(pattern::GENERATOR) << 8) | i32::from(pattern::FORMAT)
}

#[no_mangle]
extern "system" fn Java_com_github_borz7zy_rustcore_RustHaptics_nativeDatabase<'local>(
    env: JNIEnv<'local>, _class: JClass<'local>, db: JObject<'local>, operation: jint,
    key: JString<'local>, revision: JString<'local>, token: JString<'local>, value: jint,
) -> jobjectArray {
    guarded(env, null_mut(), |env| {
        let (key, revision) = source(env, &key, &revision)?;
        let token: String = env.get_string(&token)?.into();
        let values = {
            let mut guard = storage::lock()?;
            if operation == 11 {
                env.call_method(&db, "recover", "()V", &[])?;
                *guard = None;
                Vec::new()
            } else {
                let store = guard.get_or_insert_with(Store::default);
                let mut db = Db(env, &db);
                store.init(&mut db)?;
                store.operation(&mut db, operation, &key, &revision, &token, value)?
            }
        };
        let array = env.new_object_array(values.len() as i32, "java/lang/String", JObject::null())?;
        for (index, value) in values.iter().enumerate() {
            let string = env.new_string(value)?;
            env.set_object_array_element(&array, index as i32, &string)?;
            env.delete_local_ref(string)?;
        }
        Ok(array.into_raw())
    })
}

#[no_mangle]
extern "system" fn Java_com_github_borz7zy_rustcore_RustHaptics_nativeLookup<'local>(
    env: JNIEnv<'local>, _class: JClass<'local>, db: JObject<'local>,
    key: JString<'local>, revision: JString<'local>, profile: jint,
) -> jbyteArray {
    guarded(env, null_mut(), |env| {
        let (key, revision) = source(env, &key, &revision)?;
        let frames = {
            let mut guard = storage::lock()?;
            let store = guard.get_or_insert_with(Store::default);
            let mut db = Db(env, &db);
            store.init(&mut db)?;
            store.load(&mut db, &key, &revision, profile as u8)?
        };
        match frames {
            Some(frames) => Ok(env.byte_array_from_slice(&frames)?.into_raw()),
            None => Ok(null_mut()),
        }
    })
}

#[no_mangle]
extern "system" fn Java_com_github_borz7zy_rustcore_RustHaptics_nativeBegin(
    env: JNIEnv<'_>, _class: JClass<'_>, rate: jint, channels: jint, encoding: jint,
) -> jlong {
    guarded(env, 0, |_| {
        let mut active = ACTIVE.lock().map_err(|_| Error::NullPtr("haptics analyzer lock poisoned"))?;
        if active.is_some() { return Ok(0); }
        let Some(analysis) = Analysis::new(rate as usize, channels as usize, encoding as usize) else {
            return Ok(0);
        };
        let handle = NEXT.fetch_add(1, Ordering::Relaxed);
        *active = Some((handle, analysis));
        Ok(handle)
    })
}

#[no_mangle]
extern "system" fn Java_com_github_borz7zy_rustcore_RustHaptics_nativeFeed(
    env: JNIEnv<'_>, _class: JClass<'_>, handle: jlong, data: JByteBuffer<'_>, offset: jint, size: jint,
) -> jboolean {
    guarded(env, 0, |env| {
        let capacity = env.get_direct_buffer_capacity(&data)?;
        if offset < 0 || !(0..=1024 * 1024).contains(&size) || offset as usize + size as usize > capacity {
            return Ok(0);
        }
        let address = env.get_direct_buffer_address(&data)?;
        // MediaCodec owns the direct buffer until feed returns. No reference survives JNI.
        let bytes = unsafe { std::slice::from_raw_parts(address.add(offset as usize), size as usize) };
        let mut active = ACTIVE.lock().map_err(|_| Error::NullPtr("haptics analyzer lock poisoned"))?;
        Ok(u8::from(active.as_mut().filter(|(id, _)| *id == handle)
            .is_some_and(|(_, analysis)| analysis.feed(bytes).is_some())))
    })
}

#[no_mangle]
extern "system" fn Java_com_github_borz7zy_rustcore_RustHaptics_nativeSave<'local>(
    env: JNIEnv<'local>, _class: JClass<'local>, db: JObject<'local>, handle: jlong,
    key: JString<'local>, revision: JString<'local>, token: JString<'local>, limit: jint,
) -> jboolean {
    guarded(env, 0, |env| {
        let (key, revision) = source(env, &key, &revision)?;
        let token: String = env.get_string(&token)?.into();
        let analyzed = {
            let mut active = ACTIVE.lock().map_err(|_| Error::NullPtr("haptics analyzer lock poisoned"))?;
            if !active.as_ref().is_some_and(|(id, _)| *id == handle) {
                return Ok(0);
            }
            active.take().and_then(|(_, stream)| stream.finish())
        };
        let Some(analyzed) = analyzed else { return Ok(0); };
        let hash: String = analyzed.hash.iter().map(|value| format!("{value:02x}")).collect();
        let mut db = Db(env, &db);
        let exists = {
            let mut guard = storage::lock()?;
            let store = guard.get_or_insert_with(Store::default);
            store.init(&mut db)?;
            store.operation(&mut db, 2, &key, &revision, &token, storage::GENERATING)?;
            store.existing(&mut db, &hash)?
        };
        let blobs = if exists { None } else {
            let [soft, balanced, dynamic] = analyzed.synthesize();
            let encode = |profile, frames: &[u8]| {
                pattern::encode(&analyzed.hash, analyzed.duration_us, profile, frames)
                    .ok_or(Error::NullPtr("invalid generated haptics"))
            };
            Some([encode(0, &soft)?, encode(1, &balanced)?, encode(2, &dynamic)?])
        };
        let mut guard = storage::lock()?;
        let store = guard.get_or_insert_with(Store::default);
        store.operation(&mut db, 2, &key, &revision, &token, storage::SAVING)?;
        Ok(u8::from(store.save(&mut db, &key, &revision, &token, &analyzed, &hash, blobs, limit)?))
    })
}

#[no_mangle]
extern "system" fn Java_com_github_borz7zy_rustcore_RustHaptics_nativeRelease(
    env: JNIEnv<'_>, _class: JClass<'_>, handle: jlong,
) {
    guarded(env, (), |_| {
        let mut active = ACTIVE.lock().map_err(|_| Error::NullPtr("haptics analyzer lock poisoned"))?;
        if active.as_ref().is_some_and(|(id, _)| *id == handle) {
            *active = None;
        }
        Ok(())
    })
}

#[no_mangle]
extern "system" fn Java_com_github_borz7zy_rustcore_RustHaptics_nativePlan(
    env: JNIEnv<'_>, _class: JClass<'_>, frames: JByteArray<'_>, start_ms: jlong, length_ms: jlong,
    speed: jfloat, intensity: jint, mode: jint,
) -> jintArray {
    guarded(env, null_mut(), |env| {
        let size = env.get_array_length(&frames)? as usize;
        let start_ms = start_ms.clamp(-1000, (size * dsp::HOP_MS) as i64);
        let mut buffer = Vec::new();
        // Java copies only the active window instead of marshalling the full track every segment.
        let base = start_ms.max(0) as usize / 50 * 50 / dsp::HOP_MS;
        let window = (pattern::MAX_PLAN_MS as usize * 4 + 50) / dsp::HOP_MS;
        let end = (base + window).min(size);
        if end > base && size <= dsp::MAX_FRAMES {
            let mut window = vec![0i8; end - base];
            env.get_byte_array_region(&frames, base as i32, &mut window)?;
            let mode = match mode {
                1 => pattern::PlanMode::Binary,
                2 => pattern::PlanMode::NotificationBinary,
                3 => pattern::PlanMode::NotificationAmplitude,
                _ => pattern::PlanMode::Amplitude,
            };
            buffer = pattern::plan(&window.into_iter().map(|v| v as u8).collect::<Vec<_>>(),
                start_ms - (base * dsp::HOP_MS) as i64, length_ms, speed, intensity, mode);
        }
        let output = env.new_int_array(buffer.len() as i32)?;
        env.set_int_array_region(&output, 0, &buffer)?;
        Ok(output.into_raw())
    })
}

#[no_mangle]
extern "system" fn Java_com_github_borz7zy_rustcore_RustHaptics_nativeTrim(
    env: JNIEnv<'_>, _class: JClass<'_>,
) {
    guarded(env, (), |_| {
        if let Some(store) = storage::lock()?.as_mut() { store.reset(); }
        Ok(())
    })
}
