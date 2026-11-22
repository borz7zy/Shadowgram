// SPDX-FileCopyrightText: borz7zy
// SPDX-License-Identifier: MIT

use super::{dsp::Analyzed, pattern};
use jni::{
    errors::{Error, Result},
    objects::{JByteArray, JObject, JObjectArray, JString, JValue},
    JNIEnv,
};
use std::{
    collections::{HashMap, VecDeque},
    sync::{Arc, Mutex, MutexGuard},
};

pub const QUEUED: i32 = 1;
pub const ANALYZING: i32 = 3;
pub const GENERATING: i32 = 4;
pub const SAVING: i32 = 5;
pub const READY: i32 = 6;
pub const FAILED: i32 = 7;
pub const CANCELLED: i32 = 8;
const CACHE_BYTES: usize = 2 * 1024 * 1024;
type Key = (String, String, u8);

#[derive(Default)]
pub struct Store {
    initialized: bool,
    cache: VecDeque<(Key, Arc<Vec<u8>>)>,
    used: HashMap<String, String>,
    bytes: usize,
}

// JNI callers run on worker threads. Serialize short transactions and cache misses.
// DSP and compression never hold this lock.
static STORE: Mutex<Option<Store>> = Mutex::new(None);

pub fn lock() -> Result<MutexGuard<'static, Option<Store>>> {
    STORE.lock().map_err(|_| Error::NullPtr("haptics store lock poisoned"))
}

pub struct Db<'a, 'b>(pub &'a mut JNIEnv<'b>, pub &'a JObject<'b>);

impl Db<'_, '_> {
    fn args<'local>(env: &mut JNIEnv<'local>, args: &[&str]) -> Result<JObjectArray<'local>> {
        let array = env.new_object_array(args.len() as i32, "java/lang/String", JObject::null())?;
        for (i, arg) in args.iter().enumerate() {
            let value = env.new_string(arg)?;
            env.set_object_array_element(&array, i as i32, &value)?;
            env.delete_local_ref(value)?;
        }
        Ok(array)
    }

    fn execute(&mut self, sql: &str, args: &[&str], blob: Option<&[u8]>) -> Result<()> {
        self.0.with_local_frame(24, |env| {
            let sql = env.new_string(sql)?;
            let blob = blob.map(|bytes| env.byte_array_from_slice(bytes)).transpose()?;
            let args = Self::args(env, args)?;
            env.call_method(self.1, "execute", "(Ljava/lang/String;[Ljava/lang/String;[B)V", &[
                JValue::Object(&sql), JValue::Object(&args),
                JValue::Object(blob.as_ref().map_or(&JObject::null(), |array| array.as_ref())),
            ])?;
            Ok(())
        })
    }

    fn query(&mut self, sql: &str, args: &[&str]) -> Result<Vec<String>> {
        self.0.with_local_frame(24, |env| {
            let sql = env.new_string(sql)?;
            let args = Self::args(env, args)?;
            let array = JObjectArray::from(env.call_method(self.1, "query",
                "(Ljava/lang/String;[Ljava/lang/String;)[Ljava/lang/String;",
                &[JValue::Object(&sql), JValue::Object(&args)],
            )?.l()?);
            let mut values = Vec::new();
            for index in 0..env.get_array_length(&array)? {
                let value = JString::from(env.get_object_array_element(&array, index)?);
                values.push(env.get_string(&value)?.into());
                env.delete_local_ref(value)?;
            }
            Ok(values)
        })
    }

    fn blob(&mut self, sql: &str, args: &[&str]) -> Result<Option<Vec<u8>>> {
        self.0.with_local_frame(24, |env| {
            let sql = env.new_string(sql)?;
            let args = Self::args(env, args)?;
            let value = env.call_method(self.1, "blob",
                "(Ljava/lang/String;[Ljava/lang/String;)[B",
                &[JValue::Object(&sql), JValue::Object(&args)],
            )?.l()?;
            if value.is_null() { Ok(None) } else { env.convert_byte_array(JByteArray::from(value)).map(Some) }
        })
    }

    fn transaction<T>(&mut self, action: impl FnOnce(&mut Self) -> Result<T>) -> Result<T> {
        self.0.call_method(self.1, "begin", "()V", &[])?;
        let result = (|| {
            let value = action(self)?;
            self.0.call_method(self.1, "commit", "()V", &[])?;
            Ok(value)
        })();
        // Android must be able to roll back even if a Java exception is pending.
        let pending = self.0.exception_occurred()?;
        if !pending.is_null() { self.0.exception_clear()?; }
        let end = self.0.call_method(self.1, "end", "()V", &[]);
        if !pending.is_null() {
            if self.0.exception_check()? { self.0.exception_clear()?; }
            self.0.throw(pending)?;
        }
        result.and_then(|value| end.map(|_| value))
    }
}

const SCHEMA: &[&str] = &[
    "CREATE TABLE audio_assets (id INTEGER PRIMARY KEY, hash TEXT NOT NULL UNIQUE,
      duration_us INTEGER NOT NULL, sample_rate INTEGER NOT NULL, channels INTEGER NOT NULL,
      created_at INTEGER NOT NULL DEFAULT (strftime('%s','now')),
      last_used INTEGER NOT NULL DEFAULT (strftime('%s','now')))",
    "CREATE TABLE audio_sources (key TEXT PRIMARY KEY, revision TEXT NOT NULL, uri TEXT NOT NULL, pinned INTEGER NOT NULL,
      asset INTEGER REFERENCES audio_assets(id) ON DELETE SET NULL)",
    "CREATE INDEX sources_asset ON audio_sources(asset, pinned)",
    "CREATE TABLE haptic_patterns (asset INTEGER NOT NULL REFERENCES audio_assets(id) ON DELETE CASCADE,
      profile INTEGER NOT NULL CHECK(profile BETWEEN 0 AND 2), generator INTEGER NOT NULL,
      format INTEGER NOT NULL, data BLOB NOT NULL CHECK(length(data) BETWEEN 57 AND 360128),
      PRIMARY KEY(asset,profile,generator,format)) WITHOUT ROWID",
    "CREATE TABLE generation_jobs (source TEXT PRIMARY KEY REFERENCES audio_sources(key) ON DELETE CASCADE,
      revision TEXT NOT NULL, token TEXT NOT NULL DEFAULT '', status INTEGER NOT NULL,
      attempts INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL DEFAULT (strftime('%s','now')))",
    "CREATE INDEX jobs_status ON generation_jobs(status)",
    "PRAGMA user_version=1",
];

impl Store {
    pub fn init(&mut self, db: &mut Db<'_, '_>) -> Result<()> {
        if self.initialized { return Ok(()); }
        // SQLiteDatabase applies foreign_keys at the connection-pool level when its
        // public setter is used. The adapter opts into it as part of opening the DB.
        let version = db.query("PRAGMA user_version", &[])?;
        match version.first().map(String::as_str) {
            Some("0") => {
                db.execute("PRAGMA auto_vacuum=INCREMENTAL", &[], None)?;
                db.transaction(|db| {
                    for sql in SCHEMA { db.execute(sql, &[], None)?; }
                    Ok(())
                })?;
            }
            Some("1") => {}
            _ => return Err(Error::NullPtr("unsupported haptics schema")),
        }
        self.initialized = true;
        Ok(())
    }

    pub fn reset(&mut self) {
        self.cache.clear();
        self.bytes = 0;
    }

    fn versions() -> [String; 2] { [pattern::GENERATOR.to_string(), pattern::FORMAT.to_string()] }

    fn current(db: &mut Db<'_, '_>, key: &str, revision: &str) -> Result<bool> {
        let [generator, format] = Self::versions();
        let count = db.query("SELECT count(p.profile) FROM audio_sources s
            JOIN haptic_patterns p ON p.asset=s.asset
            WHERE s.key=? AND s.revision=? AND p.generator=? AND p.format=?",
            &[key, revision, &generator, &format])?;
        Ok(count.first().is_some_and(|count| count == "3"))
    }

    pub fn operation(&mut self, db: &mut Db<'_, '_>, op: i32, key: &str, rev: &str, token: &str, value: i32)
        -> Result<Vec<String>>
    {
        let number = |value: i32| Ok(vec![value.to_string()]);
        match op {
            0 => db.transaction(|db| {
                db.execute("INSERT OR IGNORE INTO audio_sources(key,revision,uri,pinned) VALUES(?,?,?,?)",
                    &[key, rev, token, if value != 0 { "1" } else { "0" }], None)?;
                db.execute("UPDATE audio_sources SET revision=?,uri=?,asset=NULL WHERE key=? AND revision<>?",
                    &[rev, token, key, rev], None)?;
                if value != 0 {
                    db.execute("UPDATE audio_sources SET pinned=1 WHERE key=?", &[key], None)?;
                }
                if Self::current(db, key, rev)? { return number(READY); }
                db.execute("INSERT OR IGNORE INTO generation_jobs(source,revision,status) VALUES(?,?,1)",
                    &[key, rev], None)?;
                db.execute("UPDATE generation_jobs SET revision=?,token='',status=1,attempts=0,
                    updated_at=strftime('%s','now') WHERE source=? AND (revision<>? OR status>=6)",
                    &[rev, key, rev], None)?;
                number(QUEUED)
            }),
            1 => db.transaction(|db| {
                if !(0..3).contains(&value) || Self::current(db, key, rev)? { return number(0); }
                db.execute("UPDATE generation_jobs SET status=2, token=?, attempts=?,
                    updated_at=strftime('%s','now') WHERE source=? AND revision=? AND status<6",
                    &[token, &(value + 1).to_string(), key, rev], None)?;
                let claims = db.query("SELECT 1 FROM generation_jobs
                    WHERE source=? AND revision=? AND token=? AND status=2", &[key, rev, token])?;
                number(i32::from(!claims.is_empty()))
            }),
            2 => {
                if matches!(value, QUEUED | ANALYZING | GENERATING | SAVING | FAILED | CANCELLED) {
                    db.execute("UPDATE generation_jobs SET status=?,updated_at=strftime('%s','now')
                        WHERE source=? AND revision=? AND token=? AND status BETWEEN 1 AND 5",
                        &[&value.to_string(), key, rev, token], None)?;
                }
                number(0)
            }
            3 => {
                if Self::current(db, key, rev)? { return number(READY); }
                let status = db.query("SELECT CASE WHEN status=6 THEN 0 ELSE status END
                    FROM generation_jobs WHERE source=? AND revision=?", &[key, rev])?;
                Ok(if status.is_empty() { vec!["0".into()] } else { status })
            }
            4 => {
                let [generator, format] = Self::versions();
                db.query("SELECT (SELECT count(*) FROM haptic_patterns WHERE generator=? AND format=?),
                    (SELECT count(*) FROM audio_sources),
                    (SELECT count(*) FROM generation_jobs WHERE status BETWEEN 1 AND 5),
                    (SELECT count(*) FROM generation_jobs WHERE status=7)", &[&generator, &format])
            }
            5 => {
                if value == -1 {
                    db.transaction(|db| {
                        db.execute("DELETE FROM audio_assets WHERE NOT EXISTS
                            (SELECT 1 FROM audio_sources s WHERE s.asset=audio_assets.id AND s.pinned=1)", &[], None)?;
                        db.execute("DELETE FROM audio_sources WHERE pinned=0", &[], None)
                    })?;
                } else {
                    self.prune(db, value)?;
                }
                self.reset();
                db.execute("PRAGMA incremental_vacuum(512)", &[], None)?;
                number(0)
            }
            6 => db.query("SELECT s.key,s.uri,s.pinned,coalesce(j.status,0)
                FROM audio_sources s LEFT JOIN generation_jobs j ON j.source=s.key
                ORDER BY s.pinned DESC,s.key LIMIT 10000", &[]),
            7 => {
                db.execute("UPDATE audio_sources SET pinned=0 WHERE key=?", &[key], None)?;
                number(0)
            }
            8 => {
                db.execute("UPDATE generation_jobs SET status=8 WHERE source=? AND revision=? AND status BETWEEN 1 AND 5",
                    &[key, rev], None)?;
                number(0)
            }
            9 => {
                db.transaction(|db| {
                    db.execute("DELETE FROM haptic_patterns", &[], None)?;
                    db.execute("UPDATE generation_jobs SET status=8 WHERE status BETWEEN 1 AND 6", &[], None)
                })?;
                self.reset();
                number(0)
            }
            10 => {
                self.flush_usage(db)?;
                number(0)
            }
            _ => number(0),
        }
    }

    pub fn load(&mut self, db: &mut Db<'_, '_>, key: &str, rev: &str, profile: u8)
        -> Result<Option<Arc<Vec<u8>>>>
    {
        if profile > 2 { return Ok(None); }
        let cache_key = (key.to_owned(), rev.to_owned(), profile);
        if let Some(index) = self.cache.iter().position(|(entry, _)| *entry == cache_key) {
            if let Some(pair) = self.cache.remove(index) {
                let frames = pair.1.clone();
                self.cache.push_back(pair);
                self.used.insert(key.to_owned(), rev.to_owned());
                return Ok(Some(frames));
            }
        }
        let [generator, format] = Self::versions();
        let profile_string = profile.to_string();
        let args = [key, rev, &profile_string, &generator, &format];
        let join = " FROM audio_sources s JOIN audio_assets a ON s.asset=a.id
            JOIN haptic_patterns p ON a.id=p.asset WHERE s.key=? AND s.revision=?
            AND p.profile=? AND p.generator=? AND p.format=?";
        let hash = db.query(&format!("SELECT a.hash{join}"), &args)?;
        let Some(hash) = hash.first().and_then(|hash| parse_hash(hash)) else { return Ok(None); };
        let blob = db.blob(&format!("SELECT p.data{join} AND length(p.data)<=360128"), &args)?;
        let Some(frames) = blob.as_deref().and_then(|bytes| pattern::decode(bytes, &hash, profile)) else {
            // Repair is queued on settings/startup only. Lookup returns a miss.
            db.execute("DELETE FROM haptic_patterns WHERE asset=(SELECT asset FROM audio_sources
                WHERE key=? AND revision=?) AND profile=? AND generator=? AND format=?", &args, None)?;
            return Ok(None);
        };
        let frames = Arc::new(frames);
        self.bytes += frames.len();
        self.cache.push_back((cache_key, frames.clone()));
        while self.bytes > CACHE_BYTES || self.cache.len() > 32 {
            if let Some((_, removed)) = self.cache.pop_front() { self.bytes -= removed.len(); }
        }
        self.used.insert(key.to_owned(), rev.to_owned());
        if self.used.len() > 128 { self.used.clear(); }
        Ok(Some(frames))
    }

    pub fn existing(&mut self, db: &mut Db<'_, '_>, hash: &str) -> Result<bool> {
        let [generator, format] = Self::versions();
        let count = db.query("SELECT count(*) FROM haptic_patterns p JOIN audio_assets a ON p.asset=a.id
            WHERE a.hash=? AND p.generator=? AND p.format=?", &[hash, &generator, &format])?;
        Ok(count.first().is_some_and(|count| count == "3"))
    }

    pub fn save(&mut self, db: &mut Db<'_, '_>, key: &str, rev: &str, token: &str,
        audio: &Analyzed, hash: &str, blobs: Option<[Vec<u8>; 3]>, limit: i32,
    ) -> Result<bool> {
        let saved = db.transaction(|db| {
            let claim = db.query("SELECT 1 FROM generation_jobs WHERE source=? AND revision=? AND token=? AND status=5",
                &[key, rev, token])?;
            if claim.is_empty() { return Ok(false); }
            if let Some(blobs) = blobs {
                db.execute("INSERT OR IGNORE INTO audio_assets(hash,duration_us,sample_rate,channels)
                    VALUES(?,?,?,?)", &[hash, &audio.duration_us.to_string(), &audio.rate.to_string(),
                    &audio.channels.to_string()], None)?;
                let [generator, format] = Self::versions();
                for (profile, blob) in blobs.into_iter().enumerate() {
                    db.execute("INSERT OR REPLACE INTO haptic_patterns(asset,profile,generator,format,data)
                        VALUES((SELECT id FROM audio_assets WHERE hash=?),?,?,?,?)",
                        &[hash, &profile.to_string(), &generator, &format], Some(&blob))?;
                }
            } else if !self.existing(db, hash)? {
                return Ok(false);
            }
            db.execute("UPDATE audio_sources SET asset=(SELECT id FROM audio_assets WHERE hash=?)
                WHERE key=? AND revision=?", &[hash, key, rev], None)?;
            db.execute("UPDATE audio_assets SET last_used=strftime('%s','now') WHERE hash=?", &[hash], None)?;
            db.execute("UPDATE generation_jobs SET status=6,updated_at=strftime('%s','now')
                WHERE source=? AND revision=? AND token=?", &[key, rev, token], None)?;
            Ok(true)
        })?;
        if saved { self.prune(db, limit)?; }
        Ok(saved)
    }

    fn flush_usage(&mut self, db: &mut Db<'_, '_>) -> Result<()> {
        if self.used.is_empty() { return Ok(()); }
        db.transaction(|db| {
            for (key, rev) in &self.used {
                db.execute("UPDATE audio_assets SET last_used=strftime('%s','now')
                    WHERE id=(SELECT asset FROM audio_sources WHERE key=? AND revision=?)",
                    &[key, rev], None)?;
            }
            Ok(())
        })?;
        self.used.clear();
        Ok(())
    }

    fn prune(&mut self, db: &mut Db<'_, '_>, limit: i32) -> Result<()> {
        self.flush_usage(db)?;
        let limit = i64::from(limit.clamp(4, 256)) * 1024 * 1024;
        let removed = db.transaction(|db| {
            let [generator, format] = Self::versions();
            db.execute("DELETE FROM haptic_patterns WHERE generator<>? OR format<>?", &[&generator, &format], None)?;
            let size = db.query("SELECT coalesce(sum(length(data)),0) FROM haptic_patterns", &[])?;
            let mut remaining = size.first().and_then(|s| s.parse::<i64>().ok()).unwrap_or(0);
            if remaining <= limit { return Ok(false); }
            let rows = db.query("SELECT a.id,coalesce(sum(length(p.data)),0) FROM audio_assets a
                LEFT JOIN haptic_patterns p ON p.asset=a.id
                WHERE NOT EXISTS(SELECT 1 FROM audio_sources s WHERE s.asset=a.id AND s.pinned=1)
                GROUP BY a.id ORDER BY a.last_used,a.id", &[])?;
            for row in rows.chunks_exact(2) {
                if remaining <= limit { break; }
                db.execute("DELETE FROM audio_assets WHERE id=?", &[&row[0]], None)?;
                remaining -= row[1].parse::<i64>().unwrap_or(0);
            }
            Ok(true)
        })?;
        if removed { self.reset(); }
        Ok(())
    }
}

fn parse_hash(hex: &str) -> Option<[u8; 32]> {
    if hex.len() != 64 || !hex.is_ascii() { return None; }
    let mut hash = [0; 32];
    for (i, byte) in hash.iter_mut().enumerate() {
        *byte = u8::from_str_radix(&hex[2*i..2*i+2], 16).ok()?;
    }
    Some(hash)
}
