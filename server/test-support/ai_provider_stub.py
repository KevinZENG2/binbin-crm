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
  text='已知事实：隔离测试资料。待确认：电话13800138000，邮箱test@example.com。'
  if 'P8_CLEAN_LEAD' in prompt:text=json.dumps({'name':'李四','companyName':'隔离验收制造','industry':'制造业','source':'展会','region':'上海','description':'仅隔离验收草稿'},ensure_ascii=False)
  elif 'P8_CLEAN_FOLLOWUP' in prompt:text=json.dumps({'content':'客户确认已收到产品资料，待核实评估流程。'},ensure_ascii=False)
  elif 'P8_UNSUPPORTED_FACT' in prompt:text='已知事实：客户将在2099-12-31付款900000元。'
  elif 'P8_SECRET_OUTPUT' in prompt:text='猎户座内部项目需要联系负责人。'
  elif 'P8_CLEAN' in prompt:text='已知事实：资料仍需核实。推断建议：确认需求。待确认事项：采购计划。'
  response={'choices':[{'message':{'content':text}}]}
  if 'MISSING_USAGE' not in prompt:response['usage']={'prompt_tokens':100,'completion_tokens':20}
  self.answer(200,response)
ThreadingHTTPServer(('127.0.0.1',8091),Handler).serve_forever()
