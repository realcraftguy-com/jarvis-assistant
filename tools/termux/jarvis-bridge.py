#!/data/data/com.termux/files/usr/bin/python
"""Jarvis ↔ Claude Code bridge (runs in Termux on the S21 FE, 127.0.0.1 only).

Started by the Jarvis app via Termux RUN_COMMAND with two secrets as arguments:
  jarvis-bridge.py <bridge_secret> <mcp_secret>

Claude Code (unmodified, signed in with the Boss' own account) is kept running in streaming mode, one process
per channel ("chat", "task"): starting it inside Debian/proot takes ~15 s, the model itself answers in ~1-2 s.
Its built-in tools are disabled; it only has Jarvis' MCP tools.

POST /ask {"channel": "chat", "system": "...", "history": "...", "prompt": "..."} -> {"result", "is_error", ...}
GET  /health -> login state
"""
import hmac
import json
import queue
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BRIDGE_SECRET, MCP_SECRET = sys.argv[1], sys.argv[2]
PORT = 8789
CLAUDE = ["proot-distro", "login", "debian", "--", "/root/.local/bin/claude"]
# passed as JSON text (no file inside the Debian rootfs needed; only visible to Termux' own user)
MCP_CONFIG = json.dumps({"mcpServers": {"jarvis": {"type": "http", "url": "http://127.0.0.1:8790/mcp",
                                                   "headers": {"Authorization": "Bearer " + MCP_SECRET}}}})
MAX_AGE = 6 * 3600      # fresh session (and memory) at least every 6 hours
MAX_TURNS = 40


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


class Worker:
    """One long-running Claude Code process in stream-json mode."""

    def __init__(self, system, model):
        self.system, self.model = system, model
        self.started, self.turns = time.time(), 0
        self.lock = threading.Lock()
        self.events = queue.Queue()
        args = ["-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
                "--model", model,
                "--tools", "",                      # no shell, files or web: only Jarvis' tools
                "--strict-mcp-config", "--mcp-config", MCP_CONFIG,
                "--allowedTools", "mcp__jarvis",
                "--permission-mode", "dontAsk",
                "--setting-sources", "",
                "--no-session-persistence",
                "--system-prompt", system]
        self.p = subprocess.Popen(CLAUDE + args, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                  text=True, bufsize=1)
        threading.Thread(target=self._read, daemon=True).start()
        log("Claude-Prozess gestartet", model)

    def _read(self):
        for line in self.p.stdout:
            line = line.strip()
            if not line:
                continue
            try:
                self.events.put(json.loads(line))
            except Exception:
                self.events.put({"type": "text", "text": line})
        self.events.put({"type": "exit"})

    def alive(self):
        return self.p.poll() is None

    def stale(self, system, model):
        return (not self.alive() or system != self.system or model != self.model
                or time.time() - self.started > MAX_AGE or self.turns >= MAX_TURNS)

    def ask(self, text, timeout):
        with self.lock:
            while not self.events.empty():
                self.events.get_nowait()
            msg = {"type": "user", "message": {"role": "user", "content": text}}
            self.p.stdin.write(json.dumps(msg) + "\n")
            self.p.stdin.flush()
            self.turns += 1
            end = time.time() + timeout
            last_text = ""
            while time.time() < end:
                try:
                    ev = self.events.get(timeout=max(0.1, end - time.time()))
                except queue.Empty:
                    break
                t = ev.get("type")
                if t == "result":
                    return ev
                if t == "exit":
                    return {"is_error": True, "result": "Claude-Prozess beendet: " + last_text[-400:]}
                if t == "text":
                    last_text += ev["text"] + "\n"
            return {"is_error": True, "result": "Zeitüberschreitung"}

    def stop(self):
        try:
            self.p.kill()
        except Exception:
            pass


WORKERS = {}
WLOCK = threading.Lock()


def worker(channel, system, model):
    with WLOCK:
        w = WORKERS.get(channel)
        fresh = w is None or w.stale(system, model)
        if fresh:
            if w:
                w.stop()
            w = WORKERS[channel] = Worker(system, model)
        return w, fresh


def claude_once(args, timeout=60):
    return subprocess.run(CLAUDE + args, capture_output=True, text=True, timeout=timeout)


class H(BaseHTTPRequestHandler):
    def _auth(self):
        return hmac.compare_digest(self.headers.get("Authorization", ""), "Bearer " + BRIDGE_SECRET)

    def _send(self, code, obj):
        b = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def log_message(self, *a):
        pass

    def do_GET(self):
        if not self._auth():
            return self._send(401, {"error": "unauthorized"})
        if self.path == "/health":
            try:
                st = claude_once(["auth", "status", "--json"], timeout=40)
                try:
                    status = json.loads(st.stdout.strip())
                except Exception:
                    status = {"raw": (st.stdout or st.stderr)[-400:]}
            except Exception as e:
                status = {"error": str(e)}
            return self._send(200, {"ok": True, "auth": status, "workers": {k: w.alive() for k, w in WORKERS.items()}})
        self._send(404, {"error": "not found"})

    def do_POST(self):
        if not self._auth():
            return self._send(401, {"error": "unauthorized"})
        req = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        if self.path == "/warm":
            worker(req.get("channel", "chat"), req.get("system", ""), req.get("model", "opus"))
            return self._send(200, {"ok": True})
        if self.path != "/ask":
            return self._send(404, {"error": "not found"})
        w, fresh = worker(req.get("channel", "chat"), req.get("system", ""), req.get("model", "opus"))
        text = req.get("prompt", "")
        if fresh and req.get("history"):
            text = req["history"] + "\n\n" + text
        if req.get("context"):
            text = "[Aktueller Kontext – nur zur Orientierung]\n" + req["context"] + "\n\n" + text
        t0 = time.time()
        res = w.ask(text, int(req.get("timeout", 150)))
        res["bridge_ms"] = int((time.time() - t0) * 1000)
        res["fresh_session"] = fresh
        log("Antwort", req.get("channel"), res["bridge_ms"], "ms", "Fehler" if res.get("is_error") else "ok")
        self._send(200, res)


ThreadingHTTPServer(("127.0.0.1", PORT), H).serve_forever()
