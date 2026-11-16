//! Disk-backed cache: one directory per bucket, one file per entry.
//!
//! Cold-start backing store for the in-memory [`crate::lru::LruCache`]. After
//! the process dies the RAM LRU is gone, but the next launch can rehydrate
//! chats / messages / avatars / sticker metadata from disk instead of going
//! to the network. Used as a write-through layer behind each cache bucket.
//!
//! Layout (`<root>/`):
//! ```text
//!   <root>/
//!     <hex(siphash(key))>.bin
//!       [1B  version]
//!       [4B  BE key_len]
//!       [N   key bytes]   ← stored so we can detect hash collisions on read
//!       […   value bytes]
//! ```
//!
//! LRU ordering is by file mtime — touched on `put` (natural — file created
//! / replaced) and on `get` via a best-effort `set_modified`. The in-memory
//! index is rebuilt on startup by scanning the directory, so a cap that was
//! shrunk between runs gets enforced immediately.
//!
//! Writes go through a `.tmp` + `rename` to avoid leaving half-written files
//! behind an interrupted process (the SQLite-style cleanup MAX itself does).

use std::collections::HashMap;
use std::fs::{self, File, OpenOptions};
use std::hash::{Hash, Hasher};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

const FORMAT_VERSION: u8 = 1;
const HEADER_LEN: u64 = 1 + 4; // version byte + 4-byte BE key length
const MAX_KEY_LEN: u32 = 64 * 1024;

#[derive(Clone)]
struct EntryMeta {
    file_name: String,
    total_size: u64,
    mtime_unix: u64,
}

pub struct DiskCache {
    root: PathBuf,
    capacity_bytes: u64,
    used_bytes: u64,
    index: HashMap<Vec<u8>, EntryMeta>,
}

fn now_unix() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

fn hash_key(key: &[u8]) -> u64 {
    use std::collections::hash_map::DefaultHasher;
    let mut h = DefaultHasher::new();
    key.hash(&mut h);
    h.finish()
}

impl DiskCache {
    pub fn new<P: AsRef<Path>>(root: P, capacity_bytes: u64) -> Self {
        let root = root.as_ref().to_path_buf();
        let _ = fs::create_dir_all(&root);
        let mut c = Self {
            root,
            capacity_bytes,
            used_bytes: 0,
            index: HashMap::new(),
        };
        c.rebuild_index();
        c.evict_to_fit();
        c
    }

    fn rebuild_index(&mut self) {
        let Ok(rd) = fs::read_dir(&self.root) else { return };
        for entry in rd.flatten() {
            let p = entry.path();
            let Ok(meta) = entry.metadata() else { continue };
            if !meta.is_file() {
                continue;
            }
            let Some(name) = p
                .file_name()
                .and_then(|n| n.to_str())
                .map(String::from)
            else {
                continue;
            };
            if !name.ends_with(".bin") {
                // Leftover .tmp from a crashed write — drop it.
                let _ = fs::remove_file(&p);
                continue;
            }
            let total = meta.len();
            let mtime = meta
                .modified()
                .ok()
                .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
                .map(|d| d.as_secs())
                .unwrap_or(0);
            match Self::read_key(&p) {
                Some(key) => {
                    self.used_bytes += total;
                    self.index.insert(
                        key,
                        EntryMeta {
                            file_name: name,
                            total_size: total,
                            mtime_unix: mtime,
                        },
                    );
                }
                None => {
                    let _ = fs::remove_file(&p);
                }
            }
        }
    }

    fn read_key(p: &Path) -> Option<Vec<u8>> {
        let mut f = File::open(p).ok()?;
        let mut header = [0u8; HEADER_LEN as usize];
        f.read_exact(&mut header).ok()?;
        if header[0] != FORMAT_VERSION {
            return None;
        }
        let klen = u32::from_be_bytes([header[1], header[2], header[3], header[4]]);
        if klen == 0 || klen > MAX_KEY_LEN {
            return None;
        }
        let mut key = vec![0u8; klen as usize];
        f.read_exact(&mut key).ok()?;
        Some(key)
    }

    pub fn put(&mut self, key: &[u8], value: &[u8]) {
        if key.is_empty() || key.len() > MAX_KEY_LEN as usize {
            return;
        }
        if let Some(prev) = self.index.remove(key) {
            self.used_bytes = self.used_bytes.saturating_sub(prev.total_size);
            let _ = fs::remove_file(self.root.join(&prev.file_name));
        }
        let file_name = format!("{:016x}.bin", hash_key(key));
        let path = self.root.join(&file_name);
        let tmp = path.with_extension("tmp");
        let total = match Self::write_atomic(&tmp, &path, key, value) {
            Some(n) => n,
            None => {
                let _ = fs::remove_file(&tmp);
                return;
            }
        };
        self.used_bytes += total;
        self.index.insert(
            key.to_vec(),
            EntryMeta {
                file_name,
                total_size: total,
                mtime_unix: now_unix(),
            },
        );
        self.evict_to_fit();
    }

    fn write_atomic(tmp: &Path, final_path: &Path, key: &[u8], value: &[u8]) -> Option<u64> {
        {
            let mut f = OpenOptions::new()
                .create(true)
                .write(true)
                .truncate(true)
                .open(tmp)
                .ok()?;
            f.write_all(&[FORMAT_VERSION]).ok()?;
            f.write_all(&(key.len() as u32).to_be_bytes()).ok()?;
            f.write_all(key).ok()?;
            f.write_all(value).ok()?;
        }
        fs::rename(tmp, final_path).ok()?;
        Some(HEADER_LEN + key.len() as u64 + value.len() as u64)
    }

    pub fn get(&mut self, key: &[u8]) -> Option<Vec<u8>> {
        let meta = self.index.get(key)?.clone();
        let path = self.root.join(&meta.file_name);
        let mut f = match File::open(&path) {
            Ok(f) => f,
            Err(_) => {
                // File vanished out from under us — heal the index.
                self.index.remove(key);
                self.used_bytes = self.used_bytes.saturating_sub(meta.total_size);
                return None;
            }
        };
        let mut header = [0u8; HEADER_LEN as usize];
        f.read_exact(&mut header).ok()?;
        if header[0] != FORMAT_VERSION {
            return None;
        }
        let klen = u32::from_be_bytes([header[1], header[2], header[3], header[4]]) as usize;
        let mut stored = vec![0u8; klen];
        f.read_exact(&mut stored).ok()?;
        if stored != key {
            // Hash collision: another key now owns this file.
            return None;
        }
        let mut v = Vec::new();
        f.read_to_end(&mut v).ok()?;
        if let Ok(touch) = OpenOptions::new().write(true).open(&path) {
            let _ = touch.set_modified(SystemTime::now());
        }
        if let Some(m) = self.index.get_mut(key) {
            m.mtime_unix = now_unix();
        }
        Some(v)
    }

    pub fn remove(&mut self, key: &[u8]) {
        if let Some(meta) = self.index.remove(key) {
            self.used_bytes = self.used_bytes.saturating_sub(meta.total_size);
            let _ = fs::remove_file(self.root.join(&meta.file_name));
        }
    }

    pub fn clear(&mut self) {
        for (_, meta) in self.index.drain() {
            let _ = fs::remove_file(self.root.join(&meta.file_name));
        }
        self.used_bytes = 0;
    }

    pub fn used_bytes(&self) -> u64 {
        self.used_bytes
    }

    fn evict_to_fit(&mut self) {
        if self.used_bytes <= self.capacity_bytes {
            return;
        }
        let mut entries: Vec<(u64, Vec<u8>)> = self
            .index
            .iter()
            .map(|(k, m)| (m.mtime_unix, k.clone()))
            .collect();
        entries.sort_by_key(|(t, _)| *t);
        for (_, key) in entries {
            if self.used_bytes <= self.capacity_bytes {
                break;
            }
            self.remove(&key);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU64, Ordering};

    static CTR: AtomicU64 = AtomicU64::new(0);

    fn tmp_root(label: &str) -> PathBuf {
        let n = CTR.fetch_add(1, Ordering::SeqCst);
        let pid = std::process::id();
        let p = std::env::temp_dir().join(format!("rustcache_disk_{}_{}_{}", label, pid, n));
        let _ = fs::remove_dir_all(&p);
        p
    }

    #[test]
    fn put_get_roundtrip() {
        let root = tmp_root("rt");
        let mut c = DiskCache::new(&root, 1024 * 1024);
        c.put(b"hello", b"world");
        assert_eq!(c.get(b"hello").as_deref(), Some(b"world".as_ref()));
        assert!(c.get(b"missing").is_none());
        fs::remove_dir_all(&root).ok();
    }

    #[test]
    fn replace_updates_size() {
        let root = tmp_root("replace");
        let mut c = DiskCache::new(&root, 1024 * 1024);
        c.put(b"k", &vec![0u8; 100]);
        let s1 = c.used_bytes();
        c.put(b"k", &vec![0u8; 200]);
        let s2 = c.used_bytes();
        assert_eq!(s2 - s1, 100);
        fs::remove_dir_all(&root).ok();
    }

    #[test]
    fn rebuild_on_restart() {
        let root = tmp_root("rebuild");
        {
            let mut c = DiskCache::new(&root, 1024 * 1024);
            c.put(b"a", b"alpha");
            c.put(b"b", b"beta");
        }
        let mut c2 = DiskCache::new(&root, 1024 * 1024);
        assert_eq!(c2.get(b"a").as_deref(), Some(b"alpha".as_ref()));
        assert_eq!(c2.get(b"b").as_deref(), Some(b"beta".as_ref()));
        fs::remove_dir_all(&root).ok();
    }

    #[test]
    fn evicts_lru_on_overflow() {
        let root = tmp_root("evict");
        // cap = 200 bytes; per entry ~ 5 (header) + 1 (key) + 50 (value) = 56
        let mut c = DiskCache::new(&root, 200);
        c.put(b"1", &vec![0u8; 50]);
        std::thread::sleep(std::time::Duration::from_millis(1100));
        c.put(b"2", &vec![0u8; 50]);
        std::thread::sleep(std::time::Duration::from_millis(1100));
        c.put(b"3", &vec![0u8; 50]);
        std::thread::sleep(std::time::Duration::from_millis(1100));
        c.put(b"4", &vec![0u8; 50]);
        // Total would be 4 * 56 = 224 > 200 — oldest "1" should go.
        assert!(c.get(b"1").is_none());
        assert!(c.get(b"4").is_some());
        fs::remove_dir_all(&root).ok();
    }

    #[test]
    fn remove_works() {
        let root = tmp_root("rm");
        let mut c = DiskCache::new(&root, 1024 * 1024);
        c.put(b"k", b"v");
        c.remove(b"k");
        assert_eq!(c.used_bytes(), 0);
        assert!(c.get(b"k").is_none());
        fs::remove_dir_all(&root).ok();
    }

    #[test]
    fn clear_drops_everything() {
        let root = tmp_root("clear");
        let mut c = DiskCache::new(&root, 1024 * 1024);
        for i in 0..10u8 {
            c.put(&[i], &vec![0u8; 10]);
        }
        c.clear();
        assert_eq!(c.used_bytes(), 0);
        for i in 0..10u8 {
            assert!(c.get(&[i]).is_none());
        }
        fs::remove_dir_all(&root).ok();
    }
}
