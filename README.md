# termux-rs

Rust rewrite exploration for Termux-style Android terminal/runtime components.

Initial scope:

- terminal protocol and input handling core
- pty/session management experiments
- Android integration boundary via JNI/NDK
- compatibility research against upstream `termux-app`

This repository starts small on purpose. Keep reusable Rust core separate from Android app glue as the design firms up.
