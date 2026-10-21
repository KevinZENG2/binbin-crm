"""Isolated localhost-only provider fixture. Never configured on live environments."""
import json,time,threading
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
calls=[];lock=threading.Lock()
class Handler(BaseHTTPRequestHandler):
 def log_message(self,*args):pass
 def answer(self,code,body):
  data=json.dumps(body,ensure_ascii=False).encode();self.send_response(code);self.send_header('Content-Type','application/json');self.end_headers();self.wfile.write(data)
 def do_GET(self):
  with lock:self.answer(200,{'calls':list(calls)})
 def do_POST(self):
  body=json.loads(self.rfile.read(int(self.headers['Content-Length'])));prompt=body['messages'][-1]['content']
  assert body['thinking']=={'type':'disabled'} and 'max_tokens' in body and 'max_completion_tokens' not in body
  assert self.headers.get('Authorization')=='Bearer crm-acceptance-only'
  with lock:calls.append(body)
  if 'SLOW' in prompt:time.sleep(0.6)
  if 'FAIL_DEFINITE' in prompt:return self.answer(429,{'error':'synthetic rejection'})
  if 'FAIL_UNCERTAIN' in prompt:return self.answer(504,{'error':'synthetic ambiguous timeout'})
  response={'choices':[{'message':{'content':'已知事实：隔离测试资料。待确认：电话13800138000，邮箱test@example.com。'}}]}
  if 'MISSING_USAGE' not in prompt:response['usage']={'prompt_tokens':100,'completion_tokens':20}
  self.answer(200,response)
ThreadingHTTPServer(('127.0.0.1',8091),Handler).serve_forever()
