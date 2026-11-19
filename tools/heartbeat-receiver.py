#!/usr/bin/env python3
"""门禁终端心跳 / 通行记录接收端（联调用）。

设备会每隔 30 秒 POST 一次心跳，识别成功后 POST 一次通行记录；
平台必须回 {"message":"ok","result":0}，否则设备会一直重推同一条记录。

用法：
    python heartbeat-receiver.py            # 默认监听 0.0.0.0:18080
    python heartbeat-receiver.py 9001       # 指定端口

把打印出来的地址填到设备的 heartBeatIp / platformIp（或 platformEnable）即可。
"""
import json
import sys
from datetime import datetime
from http.server import BaseHTTPRequestHandler, HTTPServer

sys.stdout.reconfigure(encoding='utf-8')

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 18080
REPLY = b'{"message":"ok","result":0}'


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get('Content-Length', 0))
        raw = self.rfile.read(length).decode('utf-8', 'replace')
        stamp = datetime.now().strftime('%Y-%m-%d %H:%M:%S')

        try:
            pretty = json.dumps(json.loads(raw), ensure_ascii=False, indent=2)
        except json.JSONDecodeError:
            pretty = raw

        print(f'--- {stamp}  {self.path} ---', flush=True)
        print(pretty, flush=True)
        print(flush=True)

        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(REPLY)))
        self.end_headers()
        self.wfile.write(REPLY)

    def log_message(self, *args):
        pass


if __name__ == '__main__':
    print(f'监听 0.0.0.0:{PORT} ，Ctrl+C 结束', flush=True)
    HTTPServer(('0.0.0.0', PORT), Handler).serve_forever()
