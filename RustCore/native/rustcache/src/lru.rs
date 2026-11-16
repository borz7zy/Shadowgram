//! Size-bounded LRU cache.
//!
//! Doubly-linked list of nodes stored in a `Vec<Option<Node>>` slab and indexed
//! by a `HashMap<K, usize>`. All link/unlink operations are O(1) and use
//! integer indices instead of raw pointers — no `unsafe`, no ref-counting,
//! no allocator pressure on hot paths beyond the value itself.
//!
//! Capacity is measured in bytes (caller supplies per-entry size on `put`).
//! Eviction runs from the tail until `used_bytes <= capacity_bytes`.

use std::collections::HashMap;
use std::hash::Hash;

struct Node<K, V> {
    key: K,
    value: V,
    size: usize,
    prev: Option<usize>,
    next: Option<usize>,
}

pub struct LruCache<K: Eq + Hash + Clone, V> {
    nodes: Vec<Option<Node<K, V>>>,
    free: Vec<usize>,
    map: HashMap<K, usize>,
    head: Option<usize>,
    tail: Option<usize>,
    capacity_bytes: usize,
    used_bytes: usize,
}

#[allow(dead_code)] // len/capacity_bytes are part of the LRU surface; FFI uses
                    // a packed-MiB stats accessor instead.
impl<K: Eq + Hash + Clone, V> LruCache<K, V> {
    pub fn new(capacity_bytes: usize) -> Self {
        Self {
            nodes: Vec::new(),
            free: Vec::new(),
            map: HashMap::new(),
            head: None,
            tail: None,
            capacity_bytes,
            used_bytes: 0,
        }
    }

    pub fn len(&self) -> usize {
        self.map.len()
    }

    pub fn used_bytes(&self) -> usize {
        self.used_bytes
    }

    pub fn capacity_bytes(&self) -> usize {
        self.capacity_bytes
    }

    pub fn put(&mut self, key: K, value: V, size: usize) {
        if let Some(&idx) = self.map.get(&key) {
            let old_size = self.node(idx).size;
            let n = self.node_mut(idx);
            n.value = value;
            n.size = size;
            self.used_bytes = self.used_bytes + size - old_size;
            self.detach(idx);
            self.attach_head(idx);
        } else {
            let idx = self.alloc(Node {
                key: key.clone(),
                value,
                size,
                prev: None,
                next: None,
            });
            self.map.insert(key, idx);
            self.attach_head(idx);
            self.used_bytes += size;
        }
        self.evict_to_fit();
    }

    pub fn get(&mut self, key: &K) -> Option<&V> {
        let idx = *self.map.get(key)?;
        self.detach(idx);
        self.attach_head(idx);
        Some(&self.node(idx).value)
    }

    pub fn remove(&mut self, key: &K) -> Option<V> {
        let idx = self.map.remove(key)?;
        self.detach(idx);
        let node = self.nodes[idx].take().expect("indexed slot present");
        self.free.push(idx);
        self.used_bytes -= node.size;
        Some(node.value)
    }

    pub fn clear(&mut self) {
        self.map.clear();
        self.nodes.clear();
        self.free.clear();
        self.head = None;
        self.tail = None;
        self.used_bytes = 0;
    }

    fn alloc(&mut self, node: Node<K, V>) -> usize {
        if let Some(idx) = self.free.pop() {
            self.nodes[idx] = Some(node);
            idx
        } else {
            self.nodes.push(Some(node));
            self.nodes.len() - 1
        }
    }

    fn node(&self, idx: usize) -> &Node<K, V> {
        self.nodes[idx].as_ref().expect("live slot")
    }

    fn node_mut(&mut self, idx: usize) -> &mut Node<K, V> {
        self.nodes[idx].as_mut().expect("live slot")
    }

    fn detach(&mut self, idx: usize) {
        let prev = self.node(idx).prev;
        let next = self.node(idx).next;
        match prev {
            Some(p) => self.node_mut(p).next = next,
            None => self.head = next,
        }
        match next {
            Some(n) => self.node_mut(n).prev = prev,
            None => self.tail = prev,
        }
        let n = self.node_mut(idx);
        n.prev = None;
        n.next = None;
    }

    fn attach_head(&mut self, idx: usize) {
        let old_head = self.head;
        let n = self.node_mut(idx);
        n.prev = None;
        n.next = old_head;
        match old_head {
            Some(h) => self.node_mut(h).prev = Some(idx),
            None => self.tail = Some(idx),
        }
        self.head = Some(idx);
    }

    fn evict_to_fit(&mut self) {
        while self.used_bytes > self.capacity_bytes {
            let Some(t) = self.tail else { break };
            let key = self.node(t).key.clone();
            self.remove(&key);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn put_get_basic() {
        let mut c: LruCache<String, Vec<u8>> = LruCache::new(1024);
        c.put("a".into(), vec![1, 2, 3], 3);
        assert_eq!(c.get(&"a".into()).map(|v| v.clone()), Some(vec![1, 2, 3]));
        assert_eq!(c.used_bytes(), 3);
        assert_eq!(c.len(), 1);
    }

    #[test]
    fn evicts_lru_by_bytes() {
        let mut c: LruCache<String, Vec<u8>> = LruCache::new(10);
        c.put("a".into(), vec![0; 4], 4);
        c.put("b".into(), vec![0; 4], 4);
        c.put("c".into(), vec![0; 4], 4); // total 12 -> evict "a"
        assert!(c.get(&"a".into()).is_none());
        assert!(c.get(&"b".into()).is_some());
        assert!(c.get(&"c".into()).is_some());
        assert_eq!(c.used_bytes(), 8);
    }

    #[test]
    fn get_promotes_to_head() {
        let mut c: LruCache<String, Vec<u8>> = LruCache::new(10);
        c.put("a".into(), vec![0; 4], 4);
        c.put("b".into(), vec![0; 4], 4);
        c.get(&"a".into()); // a is freshest now
        c.put("c".into(), vec![0; 4], 4); // should evict b
        assert!(c.get(&"a".into()).is_some());
        assert!(c.get(&"b".into()).is_none());
        assert!(c.get(&"c".into()).is_some());
    }

    #[test]
    fn put_overwrite_updates_size() {
        let mut c: LruCache<String, Vec<u8>> = LruCache::new(100);
        c.put("a".into(), vec![0; 4], 4);
        c.put("a".into(), vec![0; 9], 9);
        assert_eq!(c.used_bytes(), 9);
        assert_eq!(c.len(), 1);
    }

    #[test]
    fn remove_works() {
        let mut c: LruCache<i64, Vec<u8>> = LruCache::new(100);
        c.put(7, vec![0; 5], 5);
        assert_eq!(c.remove(&7).map(|v| v.len()), Some(5));
        assert_eq!(c.used_bytes(), 0);
        assert!(c.get(&7).is_none());
    }

    #[test]
    fn slab_reuses_free_slots() {
        let mut c: LruCache<i64, Vec<u8>> = LruCache::new(100);
        for i in 0..10 {
            c.put(i, vec![0; 1], 1);
        }
        for i in 0..5 {
            c.remove(&i);
        }
        for i in 100..105 {
            c.put(i, vec![0; 1], 1);
        }
        // We removed 5 and added 5; slab should not grow beyond original 10.
        assert!(c.nodes.len() <= 10);
    }
}
