#!/usr/bin/env python3
"""Serve the page. Binds every interface so a phone or the emulator can open it."""

import http.server
import os

os.chdir(os.path.dirname(os.path.abspath(__file__)))


class Handler(http.server.SimpleHTTPRequestHandler):
    extensions_map = {
        **http.server.SimpleHTTPRequestHandler.extensions_map,
        ".js": "text/javascript",
        ".wasm": "application/wasm",
        ".webmanifest": "application/manifest+json",
    }


if __name__ == "__main__":
    http.server.ThreadingHTTPServer(("0.0.0.0", 8731), Handler).serve_forever()
