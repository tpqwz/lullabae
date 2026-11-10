# lullabae

One sung or hummed line. Write the notes, then replay the take.

## Tool chain

Use rtk, context-mode, and local Qwen as one chain.

1. Run a bulky shell through `rtk` (`rtk git`, `rtk test`, `rtk log`, `rtk diff`). A short command stays a normal shell call.
2. When the payload is over about 20 lines, answer it with context-mode `ctx_execute` or `ctx_search`. The server in `~/.grok/config.toml` is `node` plus `context-mode/server.mjs`. A bare `context-mode` command prints help and the handshake dies.
3. For a local draft or a second read that fits in 4096 tokens, call Ollama `qwen2.5-coder:14b` at `http://127.0.0.1:11434/v1` (Grok model `ollama-local`). There is no `qwen` CLI.

Gather, then reduce, then draft. Pitch checks stay `g++` on `pitch-detector-skeleton`. Python is `/usr/bin/python3`.
