#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
builder="$script_dir/build-android-jni.sh"

expected_abis='arm64-v8a
armeabi-v7a
x86_64
x86'

actual_abis=$(sh "$builder" --list)
if [ "$actual_abis" != "$expected_abis" ]; then
    printf 'unexpected ABI list:\n%s\n' "$actual_abis" >&2
    exit 1
fi

assert_config() {
    abi=$1
    expected=$2
    actual=$(sh "$builder" --print-config "$abi")
    if [ "$actual" != "$expected" ]; then
        printf 'unexpected config for %s:\n%s\n' "$abi" "$actual" >&2
        exit 1
    fi
}

assert_config arm64-v8a 'aarch64-linux-android|aarch64-linux-android24-clang|aarch64-linux-android24|AARCH64_LINUX_ANDROID'
assert_config armeabi-v7a 'armv7-linux-androideabi|armv7a-linux-androideabi24-clang|armv7a-linux-androideabi24|ARMV7_LINUX_ANDROIDEABI'
assert_config x86_64 'x86_64-linux-android|x86_64-linux-android24-clang|x86_64-linux-android24|X86_64_LINUX_ANDROID'
assert_config x86 'i686-linux-android|i686-linux-android24-clang|i686-linux-android24|I686_LINUX_ANDROID'

if sh "$builder" --print-config unsupported >/dev/null 2>&1; then
    printf 'unsupported ABI unexpectedly succeeded\n' >&2
    exit 1
fi

printf 'Android ABI matrix contract passed\n'
