#!/bin/sh
set -eu

abi=${1:?usage: build-android-jni.sh <abi> <output-directory>}
output_directory=${2:?usage: build-android-jni.sh <abi> <output-directory>}
project_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

case "$abi" in
    arm64-v8a)
        rust_target=aarch64-linux-android
        clang_name=aarch64-linux-android24-clang
        ;;
    *)
        printf 'unsupported Android ABI: %s\n' "$abi" >&2
        exit 2
        ;;
esac

: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to an installed Android NDK}"
host_tag=
for candidate in darwin-arm64 darwin-x86_64 linux-x86_64 linux-aarch64; do
    if [ -x "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$candidate/bin/$clang_name" ]; then
        host_tag=$candidate
        break
    fi
done

if [ -z "$host_tag" ]; then
    printf 'Android NDK clang not found for %s\n' "$abi" >&2
    exit 2
fi

clang="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$host_tag/bin/$clang_name"
sysroot="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$host_tag/sysroot"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$clang"

cargo build --manifest-path "$project_root/Cargo.toml" --package termux-ffi --target "$rust_target" --release

mkdir -p "$output_directory/$abi"
"$clang" --target=aarch64-linux-android24 -shared -fPIC -I"$sysroot/usr/include" \
    "$project_root/android/termux-android/src/main/cpp/bootstrap_jni.c" \
    "$project_root/target/$rust_target/release/libtermux_ffi.a" \
    -llog -ldl -lm \
    -o "$output_directory/$abi/libtermux_ffi.so"
