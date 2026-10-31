"""P8-A AI platform integration acceptance, disposable localhost PostgreSQL only.
No production credentials, outbound messages, or external webhook delivery.
"""
import os,json,uuid,datetime,urllib.request,urllib.error,http.cookiejar,urllib.parse,pathlib,csv,io,base64,subprocess,time,hashlib,hmac,statistics
BASE=os.environ.get('CRM_ACCEPTANCE_URL','http://127.0.0.1:8080').rstrip('/')
assert os.environ.get('CRM_ISOLATED_ACCEPTANCE')=='true'
assert urllib.parse.urlparse(BASE).hostname in ('localhost','127.0.0.1')
PASSWORD=os.environ['CRM_TEST_PASSWORD'];checks=[];failures=[]
def uid(n):return '00000000-0000-4000-8000-'+str(n).zfill(12)
def check(name,ok=True):
 if not ok:raise AssertionError(name)
 checks.append(name);print('PASS '+name,flush=True)
def sql(statement):
 env=dict(os.environ,PGPASSWORD='acceptance-only-root-no-real-data')
 return subprocess.check_output(['psql','-h','127.0.0.1','-U','postgres','-d','crm_acceptance','-v','ON_ERROR_STOP=1','-At','-c',statement],env=env,text=True).strip()
class Client:
 def __init__(self,name=None):
  self.opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()));self.csrf='';self.tenant=''
  if name:
   d=self.req('/auth/login','POST',{'username':name,'password':PASSWORD});self.csrf=d['csrfToken']
   if name!='platform-operator':self.switch(1)
 def switch(self,n):
  self.tenant='';d=self.req('/auth/active-tenant','POST',{'tenantId':uid(n)});self.csrf=d['csrfToken'];self.tenant=uid(n)
 def req(self,path,method='GET',body=None,status=200,key=None,raw=False,headers=None):
  h={'Content-Type':'application/json','X-CSRF-Token':self.csrf,'X-Workspace-ID':self.tenant,'Idempotency-Key':key or str(uuid.uuid4())};h.update(headers or {})
  data=None if body is None else body if isinstance(body,bytes) else json.dumps(body,ensure_ascii=False).encode()
  req=urllib.request.Request(BASE+'/api/v1'+path,data=data,headers=h,method=method)
  try:
   with self.opener.open(req,timeout=60) as r:code=r.status;content=r.read()
  except urllib.error.HTTPError as e:code=e.code;content=e.read()
  if code not in (status if isinstance(status,tuple) else (status,)):raise AssertionError(f'{method} {path}: expected {status}, got {code}: {content[:900]}')
  return content if raw else json.loads(content) if content else None
anon=Client();owner=Client('owner');alice=Client('alice');viewer=Client('viewer');admin=Client('tech-admin');platform=Client('platform-operator')
from concurrent.futures import ThreadPoolExecutor

def calls():return json.load(urllib.request.urlopen('http://127.0.0.1:8091/stats'))['calls']
def prepare(instructions='',record=uid(301),client=owner):
 body={'taskType':'CUSTOMER_SUMMARY','recordId':record,'instructions':instructions,'dataApproved':True}
 preview=client.req('/ai/preview','POST',body);return dict(body,confirmationToken=preview['confirmationToken']),preview
def generate(instructions='',status=200):
 body,preview=prepare(instructions);return owner.req('/ai/runs','POST',body,status=status),body

def policy(**changes):
 old=owner.req('/ai/settings')['policy'];body={key:old[key] for key in ('enabled','modelId','monthlyTokenLimit','monthlyBudgetCents','maxInputChars','maxOutputTokens','requestsPerMinute')};body.update(expectedVersion=old['version']);body.update(changes);return owner.req('/ai/settings','PUT',body)
try:
 for path in ('/ai/settings','/platform/ai','/ai/status','/ai/runs'):anon.req(path,status=401)
 for c in (alice,viewer):c.req('/ai/settings',status=403);c.req('/platform/ai',status=403)
 owner.req('/platform/ai',status=403);platform.req('/ai/settings',status=403)
 check('AI routes enforce authentication and separate platform, enterprise-admin and sales roles')
 default=owner.req('/ai/settings');check('AI starts disabled with no hidden model or secret',not default['policy']['enabled'] and not default['models'] and 'acceptance-only' not in json.dumps(default))
 body,preview=prepare();blocked=owner.req('/ai/runs','POST',body)
 check('Unconfigured AI records BLOCKED without outbound calls or budget charges',blocked['status']=='BLOCKED' and not calls() and owner.req('/ai/settings')['usage']['usedTokens']==0)
 model={'provider':'DEEPSEEK','model':'crm-isolated-model','inputPriceCentsPerMillion':100000,'outputPriceCentsPerMillion':200000,'maxOutputTokens':512,'active':True,'expectedVersion':0}
 platform.req('/platform/ai/models','POST',dict(model,inputPriceCentsPerMillion=0),status=400)
 created=platform.req('/platform/ai/models','POST',model);model_id=created['id']
 platform.req('/platform/ai/models','POST',model,status=409)
 platform.req('/platform/ai/models','POST',dict(model,id=model_id,expectedVersion=99),status=409)
 check('Platform model settings validate prices, duplicates and optimistic versions')
 visible=platform.req('/platform/ai');check('Provider credentials never appear in platform responses','crm-acceptance-only' not in json.dumps(visible) and visible['providers'])
 old=owner.req('/ai/settings')['policy'];owner.req('/ai/settings','PUT',{'enabled':True,'modelId':model_id,'monthlyTokenLimit':100000001,'monthlyBudgetCents':10000,'maxInputChars':8000,'maxOutputTokens':512,'requestsPerMinute':60,'expectedVersion':old['version']},status=400)
 policy(enabled=True,modelId=model_id,monthlyTokenLimit=500000,monthlyBudgetCents=10000,requestsPerMinute=60)
 check('Tenant admins configure bounded model, budget and rate settings')
 body,preview=prepare('联系13800138000 aa@example.com password=syntheticsecret')
 check('Confirmation preview redacts free-text contact details and credentials',all(x not in preview['promptPreview'] for x in ('13800138000','aa@example.com','syntheticsecret')))
 owner.req('/ai/runs','POST',dict(body,dataApproved=False),status=400)
 owner.req('/ai/runs','POST',dict(body,confirmationToken=None),status=400)
 owner.req('/ai/runs','POST',dict(body,instructions='different input'),status=409)
 check('Sending requires approval and a content-bound confirmation token')
 key=str(uuid.uuid4());success=owner.req('/ai/runs','POST',body,key=key)
 check('Successful call records reported usage and redacts model output',success['status']=='SUCCEEDED' and success['inputTokens']==100 and success['outputTokens']==20 and not success['usageEstimated'] and '13800138000' not in success['resultText'])
 count=len(calls());same=owner.req('/ai/runs','POST',body,key=key)
 owner.req('/ai/runs','POST',body,status=409)
 owner.req('/ai/runs','POST',dict(body,instructions='changed'),key=key,status=409)
 check('Retries reuse one result while confirmation tokens remain single use',same['id']==success['id'] and len(calls())==count)
 stale,_=prepare();policy(requestsPerMinute=59);owner.req('/ai/runs','POST',stale,status=409)
 expired,info=prepare();token_hash=hashlib.sha256(info['confirmationToken'].encode()).hexdigest();sql(f"UPDATE sys_idempotency_request SET created_at=now()-interval '6 minutes' WHERE operation='ai.preview' AND request_key='{token_hash}'");owner.req('/ai/runs','POST',expired,status=409)
 check('Changed settings and expired confirmations prevent outbound requests')
 slow,_=prepare('SLOW');key=str(uuid.uuid4());before=len(calls())
 with ThreadPoolExecutor(2) as pool:
  responses=list(pool.map(lambda _:owner.req('/ai/runs','POST',slow,key=key),range(2)))
 check('Concurrent duplicate submissions invoke the supplier once',len(calls())==before+1 and responses[0]['id']==responses[1]['id'])
 before=owner.req('/ai/settings')['usage'];failed,body=generate('FAIL_DEFINITE',502);after=owner.req('/ai/settings')['usage']
 check('Definitive supplier rejection releases reserved tokens and reference cost',before['usedTokens']==after['usedTokens'] and before['estimatedCostCents']==after['estimatedCostCents'] and after['reservedTokens']==0 and after['reservedCostCents']==0)
 before=after;failed,body=generate('FAIL_UNCERTAIN',502);after=owner.req('/ai/settings')['usage']
 check('Ambiguous supplier failure retains conservative usage without retry',after['usedTokens']>before['usedTokens'] and after['estimatedCostCents']>before['estimatedCostCents'] and after['reservedTokens']==0)
 result,_=generate('MISSING_USAGE');check('Missing supplier usage is charged at the reserved upper bound',result['status']=='SUCCEEDED' and result['usageEstimated'])
 policy(monthlyTokenLimit=1);body,_=prepare();before=len(calls());owner.req('/ai/runs','POST',body,status=409)
 check('Insufficient token budget blocks before any supplier call',len(calls())==before)
 policy(monthlyTokenLimit=500000,monthlyBudgetCents=1);body,_=prepare();owner.req('/ai/runs','POST',body,status=409)
 check('Insufficient reference-cost budget also blocks before dispatch',len(calls())==before)
 policy(monthlyBudgetCents=10000)
 # Editing model configuration invalidates prepared send confirmations.
 body,_=prepare();platform.req('/platform/ai/models','POST',dict(model,id=model_id,active=False));owner.req('/ai/runs','POST',body,status=409)
 platform.req('/platform/ai/models','POST',dict(model,id=model_id,expectedVersion=1))
 check('Platform model changes invalidate previously approved sends')
 owner.req('/ai/runs/'+success['id']+'/feedback','PUT',{'rating':1,'notes':'联系13800138000'},status=200)
 check('Feedback is attached to authorized records and redacts notes','13800138000' not in sql(f"SELECT notes FROM crm_ai_feedback WHERE run_id='{success['id']}'"))
 owner.switch(2);other=owner.req('/ai/settings');check('Enterprise policy and usage stay isolated',not other['policy']['enabled'] and other['usage']['usedTokens']==0)
 assert all(row['id']!=success['id'] for row in owner.req('/ai/runs'));owner.switch(1)
 check('AI history cannot cross enterprise boundaries')
 # Revoke record visibility, then restore the synthetic fixture.
 old_owner=sql(f"SELECT owner_member_id FROM crm_customer WHERE id='{uid(301)}'")
 alice_body,_=prepare(client=alice);alice_run=alice.req('/ai/runs','POST',alice_body)
 sql(f"UPDATE crm_customer SET owner_member_id='{uid(202)}' WHERE id='{uid(301)}'")
 try:
  assert all(r['id']!=alice_run['id'] for r in alice.req('/ai/runs'))
  alice.req('/ai/runs/'+alice_run['id']+'/feedback','PUT',{'rating':1,'notes':''},status=404)
 finally:sql(f"UPDATE crm_customer SET owner_member_id='{old_owner}' WHERE id='{uid(301)}'")
 check('Revoked record access also removes historical AI results and feedback access')
 expiration=sql(f"SELECT subscription_end FROM sys_tenant WHERE id='{uid(1)}'");sql(f"UPDATE sys_tenant SET subscription_end=now()-interval '1 second' WHERE id='{uid(1)}'")
 try:owner.req('/ai/preview','POST',{'taskType':'CUSTOMER_SUMMARY','recordId':uid(301),'dataApproved':True},status=403)
 finally:sql(f"UPDATE sys_tenant SET subscription_end='{expiration}' WHERE id='{uid(1)}'")
 check('Expired enterprises cannot preview or initiate paid AI requests')
 check('AI policy and usage tables enforce forced tenant RLS',sql("SELECT count(*) FROM pg_class WHERE relname IN('crm_ai_policy','crm_ai_usage') AND relrowsecurity AND relforcerowsecurity")=='2')
 check('Supplier payloads contain neither tools nor plaintext contact details',all('tools' not in row and '13800138000' not in row['messages'][-1]['content'] and 'syntheticsecret' not in row['messages'][-1]['content'] for row in calls()))
 check('Every dispatch records an auditable terminal outcome',sql("SELECT count(*) FROM crm_ai_run WHERE status IN('RUNNING','PENDING')")=='0' and int(sql("SELECT count(*) FROM sys_audit_log WHERE action IN('AI_COMPLETED','AI_COST_UNCERTAIN','AI_FAILED')"))>=6)
finally:
 pathlib.Path(os.environ.get('CRM_ACCEPTANCE_RESULT','p8a-acceptance.json')).write_text(json.dumps({'phase':'P8-A','passed':len(checks),'checks':checks},ensure_ascii=False,indent=2))
