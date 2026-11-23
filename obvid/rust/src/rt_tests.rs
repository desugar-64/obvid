// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

use core::alloc::{GlobalAlloc, Layout};

use super::{MESSAGE_CAP, Malloc, panic_message};

#[test]
fn message_has_file_line_and_column() {
    let mut buf = [0xAAu8; MESSAGE_CAP];
    let m = panic_message(&mut buf, Some(("src/blur.rs", 1234, 5)));
    assert_eq!(m.to_bytes(), b"obvid panicked at src/blur.rs:1234:5");
}

#[test]
fn message_numbers_at_their_extremes() {
    let mut buf = [0u8; MESSAGE_CAP];
    let m = panic_message(&mut buf, Some(("a.rs", 0, u32::MAX)));
    assert_eq!(m.to_bytes(), b"obvid panicked at a.rs:0:4294967295");
}

#[test]
fn message_without_location() {
    let mut buf = [0u8; MESSAGE_CAP];
    let m = panic_message(&mut buf, None);
    assert_eq!(m.to_bytes(), b"obvid panicked at an unknown location");
}

#[test]
fn long_path_is_cut_and_terminated() {
    let path = "d/".repeat(200) + "x.rs";
    let mut buf = [0xAAu8; MESSAGE_CAP];
    let m = panic_message(&mut buf, Some((&path, 7, 9)));
    let bytes = m.to_bytes_with_nul();
    assert_eq!(bytes.len(), MESSAGE_CAP);
    assert!(bytes.starts_with(b"obvid panicked at d/d/"));
}

#[test]
fn nul_in_path_ends_the_message() {
    let mut buf = [0u8; MESSAGE_CAP];
    let m = panic_message(&mut buf, Some(("a\0b.rs", 1, 2)));
    assert_eq!(m.to_bytes(), b"obvid panicked at a");
}

/// Every alignment from 1 to 4096 with sizes below, at and above it: the block is aligned and writable,
/// `alloc_zeroed` zeroes it, and `realloc` (growing and shrinking) keeps its bytes and its alignment.
#[test]
fn allocator_aligns_zeroes_and_reallocates() {
    let a = Malloc;
    let mut align = 1;
    while align <= 4096 {
        for size in [1, align / 2 + 1, align, align * 3 + 1, 100_000] {
            let layout = Layout::from_size_align(size, align).unwrap();
            // SAFETY: non-zero sizes; every block is used within its size and freed once with its layout.
            unsafe {
                let p = a.alloc_zeroed(layout);
                assert!(!p.is_null());
                assert_eq!(p as usize % align, 0, "align {align} size {size}");
                assert!(core::slice::from_raw_parts(p, size).iter().all(|&b| b == 0));
                a.dealloc(p, layout);
                for new_size in [size * 2 + 7, size.div_ceil(2)] {
                    let p = a.alloc(layout);
                    assert!(!p.is_null());
                    assert_eq!(p as usize % align, 0, "align {align} size {size}");
                    for i in 0..size {
                        *p.add(i) = i as u8;
                    }
                    let q = a.realloc(p, layout, new_size);
                    assert!(!q.is_null());
                    assert_eq!(q as usize % align, 0, "realloc align {align} size {size} -> {new_size}");
                    assert!((0..size.min(new_size)).all(|i| *q.add(i) == i as u8));
                    a.dealloc(q, Layout::from_size_align(new_size, align).unwrap());
                }
            }
        }
        align *= 2;
    }
}
