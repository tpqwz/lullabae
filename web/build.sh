#!/bin/bash
# Compile the shared ear to WebAssembly and check it.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${EMSDK:-$HOME/.local/emsdk}"
# shellcheck disable=SC1091
source "$SDK/emsdk_env.sh"

cd "$ROOT/pitch-detector-skeleton"
em++ -std=c++17 -O2 \
  -s MODULARIZE=1 \
  -s EXPORT_ES6=1 \
  -s EXPORT_NAME=createLullabae \
  -s ENVIRONMENT=web,node \
  -s EXPORTED_FUNCTIONS='["_lb_init","_lb_reset","_lb_set_tuning","_lb_detect","_lb_push","_lb_describe","_lb_midi","_malloc","_free"]' \
  -s EXPORTED_RUNTIME_METHODS='["cwrap","UTF8ToString","HEAPF32","HEAPU8"]' \
  -s ALLOW_MEMORY_GROWTH=1 \
  -s FILESYSTEM=0 \
  -o "$ROOT/web/lullabae.js" \
  wasm_api.cpp PitchDetector.cpp Transcriber.cpp midi.cpp

node "$ROOT/web/check.mjs"
echo "web/lullabae.wasm is ready"
