"""A stand-in for the Replicate HTTP API, to test Re:Lad's cloud enhancement without a token or money.

Speaks the parts Re:Lad uses: file upload, predictions (by model name or version; every prediction
first says "starting", so the app has to poll), the prediction status, output download, file delete,
and the account check. "Upscaling" is a plain x2 resize with macOS `sips`, so the result is visibly
bigger but not cleaner.

usage: python3 -I fake_replicate_server.py <work dir> [port]
In a development build: Enhancement → Where to enhance → Replicate → API address
http://10.0.2.2:8766/v1 (the emulator's address of this computer), any token.
"""
import json
import re
import subprocess
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

WORK = Path(sys.argv[1])
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 8766
WORK.mkdir(parents=True, exist_ok=True)
state = {"files": 0, "predictions": {}, "next_prediction": 0}
lock = threading.Lock()


def upscale(src: Path, dst: Path):
    info = subprocess.run(["sips", "-g", "pixelWidth", "-g", "pixelHeight", str(src)], capture_output=True, text=True).stdout
    w = int(re.search(r"pixelWidth: (\d+)", info).group(1))
    h = int(re.search(r"pixelHeight: (\d+)", info).group(1))
    subprocess.run(["sips", "-s", "format", "jpeg", "-z", str(h * 2), str(w * 2), str(src), "--out", str(dst)],
                   capture_output=True, check=True)
    return w, h


class Handler(BaseHTTPRequestHandler):
    def base(self):
        return f"http://{self.headers.get('Host')}"

    def send(self, code, body=b"", content_type="application/json"):
        data = body if isinstance(body, bytes) else json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log(self, note=""):
        auth = "auth" if self.headers.get("Authorization", "").startswith("Bearer ") else "NO AUTH"
        print(f"{self.command} {self.path} [{auth}] {note}", flush=True)

    def authorized(self):
        if self.headers.get("Authorization", "").startswith("Bearer "):
            return True
        self.send(401, {"title": "Unauthenticated", "detail": "You did not pass a valid authentication token"})
        return False

    def read_body(self):
        return self.rfile.read(int(self.headers.get("Content-Length", 0)))

    def do_GET(self):
        self.log()
        if self.path == "/v1/account":
            if self.authorized():
                self.send(200, {"type": "user", "username": "fake-user"})
        elif m := re.fullmatch(r"/v1/predictions/(\w+)", self.path):
            if not self.authorized():
                return
            p = state["predictions"].get(m.group(1))
            if p is None:
                self.send(404, {"detail": "Not found"})
                return
            p["status"] = "succeeded"
            self.send(200, self.prediction(m.group(1)))
        elif m := re.fullmatch(r"/delivery/(\w+\.jpg)", self.path):
            self.send(200, (WORK / m.group(1)).read_bytes(), "image/jpeg")
        else:
            self.send(404, {"detail": "Not found"})

    def do_DELETE(self):
        self.log()
        self.send(204)

    def do_POST(self):
        body = self.read_body()
        if not self.authorized():
            self.log("rejected")
            return
        if self.path == "/v1/files":
            boundary = re.search(r"boundary=(.+)", self.headers["Content-Type"]).group(1).encode()
            part = next(p for p in body.split(b"--" + boundary) if b'name="content"' in p)
            content = part.split(b"\r\n\r\n", 1)[1].rsplit(b"\r\n", 1)[0]
            with lock:
                state["files"] += 1
                fid = f"f{state['files']}"
            (WORK / f"{fid}.img").write_bytes(content)
            self.log(f"uploaded {len(content)} bytes as {fid}")
            self.send(201, {"id": fid, "urls": {"get": f"{self.base()}/v1/files/{fid}"}})
        elif re.fullmatch(r"/v1/predictions|/v1/models/[\w.-]+/[\w.-]+/predictions", self.path):
            req = json.loads(body)
            image = next(v for v in req["input"].values() if isinstance(v, str) and "/v1/files/" in v)
            fid = image.rsplit("/", 1)[1]
            with lock:
                state["next_prediction"] += 1
                pid = f"p{state['next_prediction']}"
            w, h = upscale(WORK / f"{fid}.img", WORK / f"{pid}.jpg")
            state["predictions"][pid] = {"status": "starting", "input": req["input"], "version": req.get("version")}
            self.log(f"{pid}: {w}x{h} -> {w * 2}x{h * 2}, input={json.dumps(req['input'])}, version={req.get('version')}")
            self.send(201, self.prediction(pid))
        else:
            self.log("unknown")
            self.send(404, {"detail": "Not found"})

    def prediction(self, pid):
        p = state["predictions"][pid]
        done = p["status"] == "succeeded"
        return {
            "id": pid,
            "status": p["status"],
            "input": p["input"],
            "output": f"{self.base()}/delivery/{pid}.jpg" if done else None,
            "error": None,
            "urls": {"get": f"{self.base()}/v1/predictions/{pid}", "cancel": f"{self.base()}/v1/predictions/{pid}/cancel"},
        }

    def log_message(self, *args):
        pass


print(f"fake Replicate on :{PORT}, files in {WORK}", flush=True)
ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
