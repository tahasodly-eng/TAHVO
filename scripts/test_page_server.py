#!/usr/bin/env python3
"""Serves a test page with two videos and logs Referer / User-Agent / Cookie of every request."""
import http.server, os, sys
root, log = sys.argv[1], sys.argv[2]
class H(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **k): super().__init__(*a, directory=root, **k)
    def end_headers(self):
        if self.path.endswith('index.html'): self.send_header('Set-Cookie', 'tahvo_test=yes; Path=/')
        super().end_headers()
    def log_message(self, fmt, *args):
        with open(log, 'a') as f:
            f.write(f"{self.command} {self.path} | Referer={self.headers.get('Referer')} | UA={self.headers.get('User-Agent')} | Cookie={self.headers.get('Cookie')}\n")
http.server.ThreadingHTTPServer(('0.0.0.0', 8765), H).serve_forever()
