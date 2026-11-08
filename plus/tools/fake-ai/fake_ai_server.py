#!/usr/bin/env python3
"""Fake OpenAI-compatible server for checking Re:Lad's translation on the emulator without an API key.

Answers POST /v1/chat/completions. If the request has an image whose SHA-256 matches one of the
synthetic test pages, it answers with a hand-written translation (boxes measured from
MakeTestManga.java), wrapped in ```json fences like real models do. Without an image it answers "OK".
Logs every request (model, prompt start, image hash) to stdout.

  python3 plus/tools/fake-ai/fake_ai_server.py "<out>/ReLad Test/Chapter 1" [port]

In the app: AI provider "OpenAI-compatible", address http://10.0.2.2:8765/v1 (the host as seen
from the emulator), any model name and any key.
"""
import base64, hashlib, http.server, json, os, sys

pages_dir = sys.argv[1]
port = int(sys.argv[2]) if len(sys.argv) > 2 else 8765

# Boxes: [ymin, xmin, ymax, xmax] in 0-1000; pages are 1000 x 1500 px
CANNED = {
    "001.png": [
        {"type": "bubble", "box": [107, 170, 167, 430], "text": "こんにちは！元気？", "translation": "Привет! Как ты?"},
        {"type": "bubble", "box": [293, 600, 360, 880], "text": "今日はいい天気だね。", "translation": "Сегодня отличная погода."},
        {"type": "caption", "box": [533, 95, 567, 205], "text": "翌朝", "translation": "Наутро"},
        {"type": "bubble", "box": [593, 615, 633, 870], "text": "早く起きて！", "translation": "Вставай скорее!"},
        {"type": "sfx", "box": [740, 330, 847, 700], "text": "ドン！", "translation": "БУМ!"},
    ],
    "002.png": [
        {"type": "bubble", "box": [93, 150, 160, 370], "text": "안녕하세요! 반가워요.", "translation": "Здравствуйте! Рад знакомству."},
        {"type": "bubble", "box": [320, 630, 387, 850], "text": "你好，朋友！我们走吧。", "translation": "Привет, друг! Пошли."},
        {"type": "bubble", "box": [600, 400, 673, 600], "text": "WHAT?! No way...", "translation": "ЧТО?! Не может быть..."},
    ],
    "003.png": [
        {"type": "bubble", "box": [123, 715, 293, 825], "text": "ちょっと待って！", "translation": "Подожди-ка!"},
        {"type": "bubble", "box": [647, 180, 720, 460], "text": "どこへ行くの？", "translation": "Куда ты идёшь?"},
    ],
}

# What a real model reports besides the regions (prompt v2): new names and terms, the page summary
FINDINGS = {
    "new_characters": [{"original": "ハル", "name": "Хару", "gender": "male", "speech": "бодрый, на ты со всеми"}],
    "new_terms": [{"original": "天気", "translation": "погода", "kind": "other", "note": "test term"}],
    "summary": "Test page: friends meet in the morning.",
}

by_hash = {}
for name, regions in CANNED.items():
    with open(os.path.join(pages_dir, name), "rb") as f:
        by_hash[hashlib.sha256(f.read()).hexdigest()] = (name, regions)


class Handler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        auth = self.headers.get("Authorization", "")
        images = []
        prompt = ""
        system = ""
        for message in body.get("messages", []):
            content = message.get("content")
            if message.get("role") == "system" and isinstance(content, str):
                system += content
            if isinstance(content, list):
                for part in content:
                    if part.get("type") == "image_url":
                        url = part["image_url"]["url"]
                        images.append(base64.b64decode(url.split(",", 1)[1]))
                    elif part.get("type") == "text":
                        prompt += part["text"]
            elif isinstance(content, str) and message.get("role") == "user":
                prompt += content
        digest = hashlib.sha256(images[0]).hexdigest() if images else None
        match = by_hash.get(digest)
        print(f"model={body.get('model')} auth={'set' if auth.startswith('Bearer ') else 'missing'} "
              f"images={len(images)} page={match[0] if match else digest} prompt={prompt[:60]!r}", flush=True)
        if "# Characters" in system or "# Glossary" in system:
            print("  system prompt carries the title's notes: " +
                  ", ".join(l for l in system.splitlines() if l.startswith("- ")), flush=True)
        if not images and "fixes" in prompt:
            text = '{"fixes":[]}'
        elif not images:
            text = "OK"
        elif match:
            answer = dict(FINDINGS, regions=match[1])
            text = "Here is the translation:\n```json\n" + json.dumps(answer, ensure_ascii=False) + "\n```"
        else:
            text = '{"regions":[]}'
        answer = {"choices": [{"message": {"role": "assistant", "content": text}, "finish_reason": "stop"}]}
        data = json.dumps(answer, ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


print(f"Fake AI on port {port}, pages known: {sorted(n for n, _ in by_hash.values())}", flush=True)
http.server.ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
