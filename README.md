# termux-rs

[![CI](https://github.com/zouyonghe/termux-rs/actions/workflows/ci.yml/badge.svg)](https://github.com/zouyonghe/termux-rs/actions/workflows/ci.yml)
[![Rust Edition](https://img.shields.io/badge/rust-2024-orange.svg)](https://www.rust-lang.org/)
![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)

Rust terminal, runtime, and native-boundary building blocks for a future
Termux-compatible Android application.

This repository is not an Android app distribution. It ports reusable
Termux behavior from [termux-app](https://github.com/termux/termux-app) into
small Rust crates, keeping terminal logic independent from Android UI and JNI
adapters.

## What Exists Today

| Area | Status | Included behavior |
| --- | --- | --- |
| Terminal core | Active | Screen buffer, scrollback, Unicode cell width, ANSI/DEC parsing, OSC titles, alternate screen, scroll regions |
| Input | Active | Legacy xterm-style and Kitty keyboard encoders |
| Process transport | Active | Bounded blocking byte queue and Unix PTY prototype |
| Android boundary | Active | Opaque C ABI terminal/session handles, TRS1 render snapshots, bootstrap state machine |
| Android application | Active (vertical slice) | Single-session debug terminal: PTY shell, styled rendering, keyboard input, resize, lifecycle/exit handling — see `android/README.md` |

## Crate Map

| Crate | Responsibility |
| --- | --- |
| `termux-core` | Terminal emulator, parser, key encoding, rendering state, queues, session callbacks |
| `termux-runtime` | Termux package-aware paths and process environment values |
| `termux-pty` | Platform-isolated Unix pseudo-terminal session prototype |
| `termux-ffi` | C ABI for opaque terminal handles, input feeding, and render snapshots |
| `termux-rs` | Current command-line integration point |

## Quick Start

```sh
git clone https://github.com/zouyonghe/termux-rs.git
cd termux-rs
cargo run -p termux-rs
```

Run all host-side checks:

```sh
cargo fmt --check
cargo test --workspace
cargo clippy --workspace --all-targets -- -D warnings
```

## Compatibility Direction

`termux-rs` uses upstream `termux-app` tests and behavior as parity references,
not as a promise of complete replacement. Current work concentrates on the
terminal-emulator contract:

- UTF-8 streaming and Unicode cell width behavior
- ANSI CSI movement, erasure, styles, DECSET, alternate screen, and DECSTBM
- OSC title state plus safe bounded OSC/DCS/APC handling
- terminal input encoding and process I/O boundaries

Android-specific rendering, app lifecycle, installer state, and JNI bindings
remain separate milestones. This keeps core crates testable on macOS and Linux
without an Android SDK or device.

## Architecture

```text
termux-core -------- termux-ffi (C/JNI boundary)
     |
     +------------- terminal/parser/input/session primitives

termux-runtime ----- Termux paths and process environment
termux-pty --------- Unix PTY prototype
```

`termux-ffi` depends on `termux-core`; the other crates remain independently
testable. Android code will adapt these crates; core crates must not import
Android UI, Canvas, or JNI types.

## Development

Changes should be small, tested, and consistent with upstream behavior where a
parity reference exists. Before opening a change:

1. Add or update a focused test.
2. Run the quality gates above.
3. Keep Android-specific code outside `termux-core`.
4. Document any deliberate divergence from `termux-app` behavior.

GitHub Actions runs the same formatting, test, and clippy checks on every push
and pull request. The Android adapter now exists; wiring its NDK build and
emulator tests into CI as a separate gate is still pending.

## Roadmap

- [x] Terminal buffer, parser, Unicode, keyboard, and parity fixtures
- [x] Runtime paths, session traits, C ABI, byte queue, and Unix PTY prototype
- [x] Platform-neutral render snapshot (TRS1)
- [x] Bootstrap installer state machine
- [x] Android terminal vertical slice (single session) — `android/README.md`
- [ ] TermuxService handoff, multi-session, grid renderer, package environment

## Upstream

- [Termux application](https://github.com/termux/termux-app)
- [Termux packages](https://github.com/termux/termux-packages)
- [Termux wiki](https://wiki.termux.com/wiki/)
