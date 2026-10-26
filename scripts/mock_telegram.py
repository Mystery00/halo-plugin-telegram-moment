"""仅用于隔离容器验收的 Telegram 协议服务，不连接真实 Telegram。"""
import base64
import json
import threading
import time
import ssl
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

updates = []
lock = threading.Condition()
calls = []
fail_files = False
pixel = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=')

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def respond(self, data, code=200, raw=False):
        body = data if raw else json.dumps(data).encode()
        self.send_response(code)
        self.send_header('Content-Type', 'image/png' if raw else 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_GET(self):
        if self.path == '/__test/state':
            self.respond({'calls': calls, 'updates': len(updates)})
        elif self.path.startswith('/file/bot'):
            if fail_files:
                self.respond({'ok': False, 'error_code': 500}, 500)
            else:
                self.respond(pixel, raw=True)
        else:
            self.respond({'ok': False}, 404)

    def do_POST(self):
        global fail_files
        payload = json.loads(self.rfile.read(int(self.headers.get('Content-Length', '0'))) or '{}')
        if self.path == '/__test/updates':
            with lock:
                updates.extend(payload if isinstance(payload, list) else [payload])
                updates.sort(key=lambda value: value['update_id'])
                lock.notify_all()
            self.respond({'ok': True})
            return
        if self.path == '/__test/control':
            fail_files = payload.get('fail_files', False)
            self.respond({'ok': True})
            return
        method = self.path.rsplit('/', 1)[-1]
        calls.append(method)
        if len(calls) > 500:
            del calls[:100]
        if method == 'getMe':
            result = {'id': 123, 'is_bot': True, 'username': 'moment_test_bot', 'first_name': 'Test'}
        elif method == 'getUpdates':
            with lock:
                result = [value for value in updates if value['update_id'] >= payload.get('offset', 0)][:100]
                if not result:
                    lock.wait(1)
        elif method == 'getFile':
            result = {'file_id': payload['file_id'], 'file_path': 'files/' + payload['file_id'] + '.png', 'file_size': len(pixel)}
        elif method == 'sendMessage':
            result = {'message_id': 900000 + len(calls), 'chat': {'id': payload['chat_id'], 'type': 'private'}}
        elif method == 'deleteMessage':
            result = True
        else:
            self.respond({'ok': False, 'error_code': 404}, 404)
            return
        self.respond({'ok': True, 'result': result})

if __name__ == '__main__':
    server = ThreadingHTTPServer(('0.0.0.0', 443), Handler)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(os.environ['TEST_TLS_CERT'], os.environ['TEST_TLS_KEY'])
    server.socket = context.wrap_socket(server.socket, server_side=True)
    print('Mock Telegram listening with TLS on port 443', flush=True)
    server.serve_forever()
