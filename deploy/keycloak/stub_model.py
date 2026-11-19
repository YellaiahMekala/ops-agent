"""Local stand-in for the OpenAI chat endpoint: no network, no cost. Answers every call with a fixed structured report
and no tool calls, so a triage run ends after one step. For the local Keycloak proof only."""
import json
from http.server import BaseHTTPRequestHandler, HTTPServer

REPORT = {"findings": ["stub model: no tools were called"], "likelyCause": "unknown", "confidence": "LOW"}


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        self.rfile.read(int(self.headers.get("Content-Length", 0)))
        body = json.dumps({
            "id": "stub", "object": "chat.completion", "created": 0, "model": "stub",
            "choices": [{"index": 0, "finish_reason": "stop",
                         "message": {"role": "assistant", "content": json.dumps(REPORT)}}],
            "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
        }).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    HTTPServer(("127.0.0.1", 8199), Handler).serve_forever()
