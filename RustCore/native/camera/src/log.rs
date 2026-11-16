// SPDX-FileCopyrightText: borz7zy
// SPDX-License-Identifier: MIT

//! Stage timing to logcat (tag GcamPipeline) on Android, stderr on the host.

use std::time::Instant;

#[cfg(target_os = "android")]
#[link(name = "log")]
extern "C" {
    fn __android_log_write(prio: i32, tag: *const std::ffi::c_char, text: *const std::ffi::c_char) -> i32;
}

pub fn info(msg: &str) {
    #[cfg(target_os = "android")]
    {
        const ANDROID_LOG_INFO: i32 = 4;
        let text = std::ffi::CString::new(msg.replace('\0', " ")).unwrap_or_default();
        // SAFETY: both pointers are valid NUL-terminated strings for the call.
        unsafe { __android_log_write(ANDROID_LOG_INFO, c"GcamPipeline".as_ptr(), text.as_ptr()) };
    }
    #[cfg(not(target_os = "android"))]
    if std::env::var_os("GCAM_TIMING").is_some() {
        eprintln!("GcamPipeline: {msg}");
    }
}

pub struct Timer {
    start: Instant,
    last: Instant,
}

impl Timer {
    pub fn new() -> Self {
        let now = Instant::now();
        Timer { start: now, last: now }
    }

    pub fn lap(&mut self, stage: &str) {
        let now = Instant::now();
        info(&format!(
            "{stage}: {} ms (total {} ms)",
            (now - self.last).as_millis(),
            (now - self.start).as_millis()
        ));
        self.last = now;
    }
}
