"""P8 B-D AI workflow integration acceptance, disposable localhost PostgreSQL only.
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
owner=Client('owner');alice=Client('alice');bob=Client('bob');viewer=Client('viewer');admin=Client('tech-admin');platform=Client('platform-operator');anon=Client()
from concurrent.futures import ThreadPoolExecutor

def calls():return json.load(urllib.request.urlopen('http://127.0.0.1:8091/stats'))['calls']
def release(task,enabled=True,percent=100,prompt_id=None):
 row=next(r for r in platform.req('/platform/ai/prompts')['releases'] if r['taskType']==task)
 body={'promptId':prompt_id or row['promptId'],'enabled':enabled,'rolloutPercent':percent,'expectedVersion':row['version']}
 platform.req('/platform/ai/releases/'+task,'PUT',body);return body

def prepare(task='NEW_LEAD',record=uid(200),text='P8_CLEAN_LEAD'):
 # Move consumed synthetic previews outside the minute window so prior suites do not affect this suite.
 sql("UPDATE sys_idempotency_request SET created_at=now()-interval '2 minutes' WHERE operation='ai.preview' AND result_id IS NOT NULL")
 body={'taskType':task,'recordId':record,'instructions':text,'dataApproved':True}
 info=owner.req('/ai/preview','POST',body);return dict(body,confirmationToken=info['confirmationToken'])
def generate(task='NEW_LEAD',record=uid(200),text='P8_CLEAN_LEAD'):
 return owner.req('/ai/runs','POST',prepare(task,record,text))
def lead_body(**changes):
 lead={'name':'李四','companyName':'隔离验收制造','industry':'制造业','source':'展会','region':'上海','jobTitle':'','phone':'','email':'','description':'人工核实后的线索','ownerMemberId':uid(200)};lead.update(changes);return {'lead':lead,'activity':None,'expectedVersion':0}
def confirm(draft):return {'confirmationToken':draft['confirmationToken'],'expectedVersion':draft['version']}
def review(run,decision='ACCEPTED'):
 return owner.req('/ai/runs/'+run['id']+'/review','POST',{'decision':decision,'factualScore':4,'usefulnessScore':4,'notes':'已逐项核对客户资料和本次操作素材','expectedVersion':run['reviewVersion']})
try:
 anon.req('/ai/tasks',status=401);viewer.req('/ai/tasks',status=403);alice.req('/platform/ai/prompts',status=403);alice.req('/ai/quality-settings',status=403);platform.req('/ai/tasks',status=403)
 check('New AI routes separate operators, tenant admins, sales and read-only users')
 catalog=owner.req('/ai/tasks');tasks={t['id']:t for t in catalog['items']}
 check('Twelve scenarios reuse current module permissions',len(tasks)==12 and 'NEW_LEAD' in tasks)
 owner.req('/ai/preview','POST',{'taskType':'NEW_LEAD','recordId':uid(200),'dataApproved':True},status=409)
 check('New scenarios start closed and cannot incur provider cost before release')
 for t in tasks:release(t)
 sources=owner.req('/ai/tasks/NEW_LEAD/sources');check('Workspace tasks are bound to the acting member',sources['items'][0]['id']==uid(200))
 owner.req('/ai/preview','POST',{'taskType':'NEW_LEAD','recordId':uid(201),'dataApproved':True},status=404)
 viewer.req('/ai/tasks/NEW_LEAD/sources',status=403)
 check('Forged member workspace IDs and viewer access are rejected')
 # Create scenario fixtures through canonical APIs instead of depending on unrelated suites.
 due=str(datetime.date.today()+datetime.timedelta(days=7))
 owner.req('/opportunities','POST',{'customerId':uid(301),'sourceLeadId':None,'title':'P8场景商机验收','ownerMemberId':uid(200),'amountCents':10000,'closeDate':due,'nextAction':'核实需求','description':'隔离素材'})
 owner.req('/campaigns','POST',{'name':'P8活动建议验收','description':'隔离素材','ownerMemberId':uid(200),'channel':'FOLLOW_UP','sourceChannel':'EVENT','budgetCents':10000,'plannedStart':str(datetime.date.today()),'plannedEnd':due,'subject':'需求核实','body':'仅供隔离测试，不对外发送','taskDueDate':due,'expectedVersion':0})
 for task in ('CUSTOMER_SUMMARY','OPPORTUNITY_COACH','TICKET_REPLY','TICKET_TRIAGE','CAMPAIGN_ADVICE','LEAD_ASSESSMENT','VISIT_PREP','FOLLOWUP_DRAFT','WEEKLY_SUMMARY','ANOMALY_EXPLAIN'):
  rows=owner.req('/ai/tasks/'+task+'/sources')['items']
  if not rows:raise AssertionError('Missing canonical fixture for '+task)
  result=generate(task,rows[0]['id'],'P8_CLEAN')
  check(task+' uses authorized source data and records a prompt version',result['status']=='SUCCEEDED' and result['promptVersionId'] and result['qualityStatus']=='PASS')
 # Approvals use the designated reviewer, never an owner bypass.
 product=owner.req('/products?page=1&pageSize=100&q=&status=')['items'][0]
 until=str(datetime.date.today()+datetime.timedelta(days=30))
 quote=alice.req('/quotes','POST',{'customerId':uid(301),'title':'P8审批辅助验收','validUntil':until,'notes':'隔离数据','lines':[{'productId':product['id'],'quantity':1,'unitPriceCents':product['priceCents'],'discountBps':0,'description':''}],'expectedVersion':0})
 quote=alice.req('/quotes/'+quote['id']+'/submit','POST',{'expectedVersion':quote['version'],'approverMemberId':uid(200),'reason':''})
 result=generate('APPROVAL_ASSIST',quote['id'],'P8_CLEAN')
 alice.req('/ai/preview','POST',{'taskType':'APPROVAL_ASSIST','recordId':quote['id'],'dataApproved':True},status=403)
 unchanged=owner.req('/quotes/'+quote['id'])
 check('Approval assistance checks designated reviewer and never approves the quote',result['status']=='SUCCEEDED' and unchanged['status']=='SUBMITTED')
 # Editing a draft is not a business mutation, and confirmation is bound to stored typed fields.
 run=generate();before=int(sql("SELECT count(*) FROM crm_lead"));body=lead_body();draft=owner.req('/ai/runs/'+run['id']+'/proposals','POST',body)
 check('Preparing an editable lead draft does not create a lead',int(sql("SELECT count(*) FROM crm_lead"))==before)
 alice.req('/ai/proposals/'+draft['id']+'/confirm','POST',confirm(draft),status=404)
 owner.req('/ai/proposals/'+draft['id']+'/confirm','POST',dict(confirm(draft),confirmationToken='wrong'),status=409)
 with ThreadPoolExecutor(2) as pool:results=list(pool.map(lambda _:owner.req('/ai/proposals/'+draft['id']+'/confirm','POST',confirm(draft)),range(2)))
 check('Concurrent and repeated confirmation create exactly one lead',results[0]['resultId']==results[1]['resultId'] and int(sql("SELECT count(*) FROM crm_lead"))==before+1)
 owner.req('/ai/runs/'+run['id']+'/proposals','POST',body,status=409)
 check('An applied AI run cannot be reused to create a second lead')
 run=generate();owner.req('/ai/runs/'+run['id']+'/proposals','POST',lead_body(ownerMemberId=uid(201)),status=400)
 draft=owner.req('/ai/runs/'+run['id']+'/proposals','POST',lead_body());sql("UPDATE crm_ai_proposal SET expires_at=now()-interval '1 second' WHERE id='"+draft['id']+"'")
 owner.req('/ai/proposals/'+draft['id']+'/confirm','POST',confirm(draft),status=409)
 check('Lead owner forgery and expired save confirmations are blocked')
 # Source edits invalidate precomputed follow-up drafts.
 follow=generate('FOLLOWUP_DRAFT',uid(301),'P8_CLEAN_FOLLOWUP')
 activity={'customerId':uid(301),'opportunityId':None,'activityType':'CALL','content':'客户已收到资料，待核实评估流程','occurredAt':datetime.datetime.now(datetime.timezone.utc).isoformat()}
 draft=owner.req('/ai/runs/'+follow['id']+'/proposals','POST',{'activity':activity,'lead':None,'expectedVersion':0})
 sql("UPDATE crm_customer SET version=version+1 WHERE id='"+uid(301)+"'")
 owner.req('/ai/proposals/'+draft['id']+'/confirm','POST',confirm(draft),status=409)
 check('Changed source records invalidate pending business saves')
 follow=generate('FOLLOWUP_DRAFT',uid(301),'P8_CLEAN_FOLLOWUP');body={'activity':activity,'lead':None,'expectedVersion':0};draft=owner.req('/ai/runs/'+follow['id']+'/proposals','POST',body)
 result=owner.req('/ai/proposals/'+draft['id']+'/confirm','POST',confirm(draft));activity_id=result['resultId']
 check('Confirmed follow-up uses the existing authorized activity API',sql("SELECT customer_id FROM crm_activity WHERE id='"+activity_id+"'")==uid(301))
 # Proposed factual claims require a supervisor, while secret-policy blocks are never overridable.
 suspect=generate('FOLLOWUP_DRAFT',uid(301),'P8_UNSUPPORTED_FACT');owner.req('/ai/runs/'+suspect['id']+'/proposals','POST',body,status=409)
 check('Unsupported dates and money require review before a business save',suspect['qualityStatus']=='REVIEW')
 alice.req('/ai/runs/'+suspect['id']+'/review','POST',{'decision':'ACCEPTED','factualScore':5,'usefulnessScore':5,'notes':'尝试绕过主管复核权限','expectedVersion':0},status=(403,404))
 review(suspect);history=owner.req('/ai/runs/'+suspect['id']+'/evaluations')
 draft=owner.req('/ai/runs/'+suspect['id']+'/proposals','POST',body)
 owner.req('/ai/proposals/'+draft['id']+'/cancel?expectedVersion=0','POST')
 check('Supervisor review records scores and cancelled drafts cannot execute',history[0]['decision']=='ACCEPTED')
 owner.req('/ai/proposals/'+draft['id']+'/confirm','POST',confirm(draft),status=409)
 p=owner.req('/ai/quality-settings');owner.req('/ai/quality-settings','PUT',{'sensitiveTerms':['猎户座'],'strictFacts':True,'expectedVersion':p['version']})
 before=owner.req('/ai/settings')['usage']['usedTokens'];secret=generate('NEW_LEAD',uid(200),'P8_SECRET_OUTPUT');after=owner.req('/ai/settings')['usage']['usedTokens']
 check('Sensitive output is blocked and provider usage is still settled',secret['qualityStatus']=='BLOCKED' and '猎户座' not in secret['resultText'] and after>before)
 owner.req('/ai/runs/'+secret['id']+'/review','POST',{'decision':'ACCEPTED','factualScore':5,'usefulnessScore':5,'notes':'阻断结果不能通过人工覆盖','expectedVersion':0},status=409)
 check('Supervisor acceptance cannot bypass a sensitive-information block')
 prepared=prepare();release('NEW_LEAD',enabled=False);before=len(calls());owner.req('/ai/runs','POST',prepared,status=409)
 check('Emergency scenario shutdown stops prepared calls before dispatch',len(calls())==before)
 release('NEW_LEAD',percent=0);owner.req('/ai/preview','POST',{'taskType':'NEW_LEAD','recordId':uid(200),'dataApproved':True},status=409)
 release('NEW_LEAD');check('Zero-percent rollout excludes all tenants; full release restores eligibility')
 row=next(r for r in platform.req('/platform/ai/prompts')['releases'] if r['taskType']=='NEW_LEAD');old=row['promptId']
 new=platform.req('/platform/ai/prompts','POST',{'taskType':'NEW_LEAD','title':'隔离验收新版本','instruction':'仅整理明确事实，未知字段留空。'})['id'];release('NEW_LEAD',prompt_id=new)
 row=next(r for r in platform.req('/platform/ai/prompts')['releases'] if r['taskType']=='NEW_LEAD');platform.req('/platform/ai/releases/NEW_LEAD/rollback','POST',{'promptId':new,'enabled':True,'rolloutPercent':100,'expectedVersion':row['version']})
 row=next(r for r in platform.req('/platform/ai/prompts')['releases'] if r['taskType']=='NEW_LEAD')
 check('Prompt rollback restores the previous immutable version',row['promptId']==old and row['previousPromptId']==new)
 before=len(calls());r=platform.req('/platform/ai/rule-test','POST',{'source':'未知回款日期','output':'确定在2099-12-31回款900000元'})
 check('Offline quality evaluation is explicit and never calls a supplier',r['mode']=='OFFLINE_RULES_ONLY' and not r['supplierCalled'] and r['result']['status']=='REVIEW' and len(calls())==before)
 owner.switch(2);owner.req('/ai/runs/'+run['id']+'/evaluations',status=404);owner.req('/ai/proposals/'+draft['id']+'/confirm','POST',confirm(draft),status=404);owner.switch(1)
 check('Evaluations and proposals cannot cross enterprise boundaries')
 check('V30 adds forced RLS to every new tenant table',sql("SELECT count(*) FROM pg_class WHERE relname IN('crm_ai_quality_policy','crm_ai_evaluation','crm_ai_proposal') AND relrowsecurity AND relforcerowsecurity")=='3')
 check('All proposed actions have explicit audit events',int(sql("SELECT count(*) FROM sys_audit_log WHERE action='AI_PROPOSAL_APPLIED'"))==2)
finally:
 pathlib.Path(os.environ.get('CRM_ACCEPTANCE_RESULT','p8-complete-acceptance.json')).write_text(json.dumps({'phase':'P8 B-D','passed':len(checks),'checks':checks},ensure_ascii=False,indent=2)+'\n')
