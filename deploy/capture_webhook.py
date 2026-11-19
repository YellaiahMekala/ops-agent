# Copyright (c) 2026 the owner of this repository. All rights reserved.
# SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
# Canary: ops-agent-canary-f59f1ff0dca746d68a51
"""Recording only: accept Alertmanager webhook POSTs on 127.0.0.1:9099 and save each body as-is.

Usage: python deploy/capture_webhook.py <output-dir>
Each request is written to <output-dir>/capture-<n>.json. No headers are saved (the capture receiver sends none
that matter, and the ops-agent receiver's bearer token never reaches this server).
"""
import http.server
import pathlib
import sys

OUT = pathlib.Path(sys.argv[1])
OUT.mkdir(parents=True, exist_ok=True)
COUNT = [0]


class Handler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        COUNT[0] += 1
        (OUT / f"capture-{COUNT[0]}.json").write_bytes(body)
        self.send_response(200)
        self.end_headers()

    def log_message(self, *args):
        pass


http.server.ThreadingHTTPServer(("0.0.0.0", 9099), Handler).serve_forever()
