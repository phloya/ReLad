#!/usr/bin/env python3
"""Replays a real model's answers to the app, to see them in the reader without an API key in it.

LiveTranslationTest (plus/translate) saves each raw answer as raw/<chapter>-<page>-<n>.txt. This
server answers the app's OpenAI-format requests: a page image whose SHA-256 matches one of the pages
gets that page's saved answer; a text-only request (the second look) gets the saved second answer
or an empty list of fixes.

    python3 -I replay_server.py <live test out dir> <chapter dir> [<chapter dir> ...] [--port 8765]

In the app: AI provider "OpenAI-compatible", address http://10.0.2.2:8765/v1, any model and key.
"""
import base64
import hashlib
import http.server
import json
import os
import sys

args = sys.argv[1:]
port = 8765
if "--port" in args:
    i = args.index("--port")
    port = int(args[i + 1])
    del args[i:i + 2]
out_dir, chapter_dirs = args[0], args[1:]

answers = {}
for chapter_dir in chapter_dirs:
    chapter = os.path.basename(os.path.normpath(chapter_dir))
    for name in sorted(os.listdir(chapter_dir)):
        stem, ext = os.path.splitext(name)
        raw = os.path.join(out_dir, "raw", f"{chapter}-{stem}-0.txt")
        if ext.lower() not in (".jpg", ".jpeg", ".png", ".webp") or not os.path.exists(raw):
            continue
        with open(os.path.join(chapter_dir, name), "rb") as f:
            digest = hashlib.sha256(f.read()).hexdigest()
        second = os.path.join(out_dir, "raw", f"{chapter}-{stem}-1.txt")
        answers[digest] = (f"{chapter}/{name}", open(raw, encoding="utf-8").read(),
                           open(second, encoding="utf-8").read() if os.path.exists(second) else None)
last_page = {}


class Handler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        images = []
        for message in body.get("messages", []):
            content = message.get("content")
            if isinstance(content, list):
                for part in content:
                    if part.get("type") == "image_url":
                        images.append(base64.b64decode(part["image_url"]["url"].split(",", 1)[1]))
        if images:
            match = answers.get(hashlib.sha256(images[0]).hexdigest())
            last_page["value"] = match
            text = match[1] if match else '{"regions":[]}'
            print(f"page {match[0] if match else 'unknown'}", flush=True)
        else:
            match = last_page.get("value")
            text = match[2] if match and match[2] else '{"fixes":[]}'
            print("second look", flush=True)
        data = json.dumps({"choices": [{"message": {"role": "assistant", "content": text},
                                        "finish_reason": "stop"}]}, ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


print(f"Replaying {len(answers)} pages on port {port}", flush=True)
http.server.ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
