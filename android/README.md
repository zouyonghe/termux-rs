# Android Library

The `termux-android` library targets API 24+ and currently packages one native
ABI: `arm64-v8a`.

Build the JNI library and debug Android artifact with an installed Android SDK
and NDK:

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/29.0.14206865"
cd android
gradle :termux-android:assembleDebug
```

Run adapter JVM tests:

```sh
cd android
gradle :termux-android:testDebugUnitTest
```

`build-android-jni.sh` cross-compiles `termux-ffi`, links its static Rust
library with the JNI shim, and writes `libtermux_ffi.so` under the Gradle build
directory. No generated native artifact is checked in.
