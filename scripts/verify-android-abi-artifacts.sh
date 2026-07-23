#!/bin/sh
set -eu

archive=${1:?usage: verify-android-abi-artifacts.sh <aar-or-apk>}
if [ ! -f "$archive" ]; then
    printf 'Android artifact not found: %s\n' "$archive" >&2
    exit 1
fi

entries=$(unzip -Z1 "$archive")
for abi in arm64-v8a armeabi-v7a x86_64 x86; do
    expected="jni/$abi/libtermux_ffi.so"
    case "
$entries
" in
        *"
$expected
"*) ;;
        *)
            printf 'missing %s in %s\n' "$expected" "$archive" >&2
            exit 1
            ;;
    esac
done

printf 'Verified Android ABI matrix in %s\n' "$archive"
