// SPDX-FileCopyrightText: borz7zy
// SPDX-License-Identifier: MIT

//! Render every frame of a TGS for profiling:
//! `bench <file.tgs> [size=512] [loops=3] [dump.rgba]`.
//! Prints load (cold and from rlottie's model cache) and per-frame timing; `dump.rgba` gets frame 0 as raw RGBA.

use std::time::Instant;

fn main() {
    let mut args = std::env::args().skip(1);
    let path = args.next().expect("usage: bench <file.tgs> [size] [loops] [dump.rgba]");
    let size: usize = args.next().map_or(512, |s| s.parse().unwrap());
    let loops: usize = args.next().map_or(3, |s| s.parse().unwrap());
    let dump = args.next();
    let data = std::fs::read(&path).unwrap();

    let t = Instant::now();
    let mut a = dec_tgs::Animation::new(&data, &path).unwrap();
    let load = t.elapsed();
    // Same key again: rlottie's model cache skips the JSON parse.
    let t = Instant::now();
    drop(dec_tgs::Animation::new(&data, &path).unwrap());
    let reload = t.elapsed();
    let mut px = vec![0u32; size * size];
    if let Some(dump) = dump {
        a.render(0, &mut px, size, size, size);
        std::fs::write(dump, px.iter().flat_map(|p| p.to_ne_bytes()).collect::<Vec<_>>()).unwrap();
    }

    let mut worst = 0f64;
    let t = Instant::now();
    for _ in 0..loops {
        for i in 0..a.frame_count {
            let f = Instant::now();
            a.render(i, &mut px, size, size, size);
            worst = worst.max(f.elapsed().as_secs_f64() * 1e3);
        }
    }
    let n = (loops * a.frame_count) as f64;
    let avg = t.elapsed().as_secs_f64() * 1e3 / n;
    println!(
        "{path}: {}x{} → {size}², {} frames @ {:.0} fps, load {:.2} ms, cached load {:.2} ms, render avg {avg:.3} ms, worst {worst:.3} ms",
        a.width, a.height, a.frame_count, a.fps, load.as_secs_f64() * 1e3, reload.as_secs_f64() * 1e3
    );
}
