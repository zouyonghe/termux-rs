#!/bin/sh
set -eu

project_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

list_abis() {
    printf '%s\n' arm64-v8a armeabi-v7a x86_64 x86
}

configure_abi() {
    abi=$1
    case "$abi" in
    arm64-v8a)
        rust_target=aarch64-linux-android
        clang_name=aarch64-linux-android24-clang
        clang_target=aarch64-linux-android24
        linker_key=AARCH64_LINUX_ANDROID
        ;;
    armeabi-v7a)
        rust_target=armv7-linux-androideabi
        clang_name=armv7a-linux-androideabi24-clang
        clang_target=armv7a-linux-androideabi24
        linker_key=ARMV7_LINUX_ANDROIDEABI
        ;;
    x86_64)
        rust_target=x86_64-linux-android
        clang_name=x86_64-linux-android24-clang
        clang_target=x86_64-linux-android24
        linker_key=X86_64_LINUX_ANDROID
        ;;
    x86)
        rust_target=i686-linux-android
        clang_name=i686-linux-android24-clang
        clang_target=i686-linux-android24
        linker_key=I686_LINUX_ANDROID
        ;;
    *)
        printf 'unsupported Android ABI: %s\n' "$abi" >&2
        exit 2
        ;;
    esac
}

case "${1:-}" in
    --list)
        list_abis
        exit 0
        ;;
    --print-config)
        configure_abi "${2:?usage: build-android-jni.sh --print-config <abi>}"
        printf '%s|%s|%s|%s\n' "$rust_target" "$clang_name" "$clang_target" "$linker_key"
        exit 0
        ;;
esac

abi=${1:?usage: build-android-jni.sh <abi|all> <output-directory>}
output_directory=${2:?usage: build-android-jni.sh <abi|all> <output-directory>}

if [ "$abi" = all ]; then
    for supported_abi in $(list_abis); do
        sh "$0" "$supported_abi" "$output_directory"
    done
    exit 0
fi

configure_abi "$abi"

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
eval "export CARGO_TARGET_${linker_key}_LINKER=\$clang"

cargo build --manifest-path "$project_root/Cargo.toml" --package termux-ffi --target "$rust_target" --release

mkdir -p "$output_directory/$abi"
"$clang" --target="$clang_target" -shared -fPIC -I"$sysroot/usr/include" \
    "$project_root/android/termux-android/src/main/cpp/bootstrap_jni.c" \
    "$project_root/target/$rust_target/release/libtermux_ffi.a" \
    -llog -ldl -lm \
    -o "$output_directory/$abi/libtermux_ffi.so"
