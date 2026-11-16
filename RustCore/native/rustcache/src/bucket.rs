//! Two-tier cache bucket: in-memory [`LruCache`] over an optional [`DiskCache`].
//!
//! The memory tier is the hot path (lock + HashMap probe), the disk tier is the
//! cold backing store that survives process death. Writes are write-through:
//! `put` lands in RAM and on disk simultaneously, so a kill -9 or low-memory
//! reboot doesn't cost us the bytes we just cached.
//!
//! Reads check RAM first; on a miss we hit the disk and promote the bytes back
//! into RAM so the next access stays in-process. Disk-tier installation is
//! optional — until `install_disk` is called the bucket behaves as a pure RAM
//! LRU (lets us use the bucket safely before `RustCache.init` has run).

use std::path::PathBuf;

use crate::disk::DiskCache;
use crate::lru::LruCache;

pub struct Bucket {
    mem: LruCache<Vec<u8>, Vec<u8>>,
    disk: Option<DiskCache>,
    disk_cap: u64,
}

impl Bucket {
    pub fn new(mem_cap_bytes: usize, disk_cap_bytes: u64) -> Self {
        Self {
            mem: LruCache::new(mem_cap_bytes),
            disk: None,
            disk_cap: disk_cap_bytes,
        }
    }

    /// No-op for RAM-only buckets (`disk_cap == 0`): nothing to write through.
    pub fn install_disk(&mut self, root: PathBuf) {
        if self.disk_cap == 0 {
            return;
        }
        self.disk = Some(DiskCache::new(root, self.disk_cap));
    }

    pub fn put(&mut self, key: Vec<u8>, value: Vec<u8>) {
        if let Some(d) = &mut self.disk {
            d.put(&key, &value);
        }
        let size = value.len();
        self.mem.put(key, value, size);
    }

    pub fn get(&mut self, key: &[u8]) -> Option<Vec<u8>> {
        if let Some(v) = self.mem.get(&key.to_vec()) {
            return Some(v.clone());
        }
        let disk = self.disk.as_mut()?;
        let v = disk.get(key)?;
        let size = v.len();
        // Promote disk hit into RAM so the next probe stays in-process.
        self.mem.put(key.to_vec(), v.clone(), size);
        Some(v)
    }

    pub fn remove(&mut self, key: &[u8]) {
        let _ = self.mem.remove(&key.to_vec());
        if let Some(d) = &mut self.disk {
            d.remove(key);
        }
    }

    pub fn clear(&mut self) {
        self.mem.clear();
        if let Some(d) = &mut self.disk {
            d.clear();
        }
    }

    /// Drop only the RAM tier — disk tier survives. Used as the response to
    /// Android `onTrimMemory`: we give back the heap pressure but preserve
    /// the cold cache so the next launch / re-use still benefits.
    pub fn clear_mem_only(&mut self) {
        self.mem.clear();
    }

    pub fn used_mem_bytes(&self) -> usize {
        self.mem.used_bytes()
    }

    pub fn used_disk_bytes(&self) -> u64 {
        self.disk.as_ref().map(|d| d.used_bytes()).unwrap_or(0)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn durable_keeps_everything_and_survives_reopen() {
        let root = std::env::temp_dir().join(format!("rustcache_bucket_{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        {
            // Tiny RAM tier, unbounded disk — RAM evicts, disk must not.
            let mut b = Bucket::new(16, u64::MAX);
            b.install_disk(root.clone());
            for i in 0..100u8 {
                b.put(vec![i], vec![i; 64]);
            }
        }
        let mut b = Bucket::new(16, u64::MAX);
        b.install_disk(root.clone());
        for i in 0..100u8 {
            assert_eq!(b.get(&[i]), Some(vec![i; 64]));
        }
        std::fs::remove_dir_all(&root).ok();
    }
}
