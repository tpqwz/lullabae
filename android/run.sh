#!/bin/bash
# Build lullabae, install it on the running emulator, and launch it.
set -euo pipefail

cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-$HOME/.android-sdk}"
ADB="$SDK/platform-tools/adb"

./build.sh

"$ADB" wait-for-device
"$ADB" shell input keyevent KEYCODE_WAKEUP >/dev/null
"$ADB" install -r -g build/lullabae.apk
"$ADB" shell am start -W -n dev.lullabae/.MainActivity
echo "lullabae is running"
