#!/bin/bash
# Build lullabae.apk from the shared C++ pitch detector.
# Output: android/build/lullabae.apk
set -euo pipefail

cd "$(dirname "$0")"
ROOT="$(pwd)"
REPO="$(cd "$ROOT/.." && pwd)"
SDK="${ANDROID_HOME:-$HOME/.android-sdk}"
BT="$SDK/build-tools/36.0.0"
PLATFORM="$SDK/platforms/android-36/android.jar"
NDK="$SDK/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin"
CORE="$REPO/pitch-detector-skeleton"
OUT="$ROOT/build"

if [[ ! -x "$NDK/x86_64-linux-android26-clang++" ]]; then
    echo "NDK clang not found under $SDK" >&2
    exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/gen" "$OUT/lib/x86_64" "$OUT/lib/arm64-v8a"

compile_abi() {
    local abi="$1"
    local triple="$2"
    "$NDK/${triple}26-clang++" -std=c++17 -O2 -fPIC -shared -static-libstdc++ \
        -Wall -Wextra -Wno-unused-parameter \
        -Wl,-z,max-page-size=16384 \
        -I"$CORE" \
        -o "$OUT/lib/$abi/liblullabae.so" \
        "$ROOT/jni/pitch_jni.cpp" \
        "$CORE/PitchDetector.cpp" \
        "$CORE/Transcriber.cpp" \
        "$CORE/midi.cpp" \
        "$CORE/replay.cpp"
}

echo "compiling native"
compile_abi x86_64 x86_64-linux-android
compile_abi arm64-v8a aarch64-linux-android

echo "packaging resources"
"$BT/aapt2" compile -o "$OUT/res.zip" --dir "$ROOT/res"
"$BT/aapt2" link -o "$OUT/linked.apk" \
    -I "$PLATFORM" \
    --manifest "$ROOT/AndroidManifest.xml" \
    --java "$OUT/gen" \
    --min-sdk-version 26 \
    --target-sdk-version 36 \
    "$OUT/res.zip"

echo "compiling java"
javac --release 11 -classpath "$PLATFORM" -d "$OUT/classes" \
    "$ROOT"/src/dev/lullabae/*.java \
    "$OUT"/gen/dev/lullabae/R.java

echo "dexing"
"$BT/d8" --lib "$PLATFORM" --min-api 26 --output "$OUT" \
    $(find "$OUT/classes" -name '*.class')

cp "$OUT/linked.apk" "$OUT/unsigned.apk"
(
    cd "$OUT"
    zip -q unsigned.apk classes.dex
    zip -q -r unsigned.apk lib
)
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

KS="$ROOT/debug.keystore"
if [[ ! -f "$KS" ]]; then
    keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=lullabae debug" >/dev/null
fi
"$BT/apksigner" sign \
    --ks "$KS" --ks-pass pass:android \
    --key-pass pass:android --ks-key-alias androiddebugkey \
    --out "$OUT/lullabae.apk" "$OUT/aligned.apk"

echo "built $OUT/lullabae.apk"
