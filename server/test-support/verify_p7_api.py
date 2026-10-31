"""P7 B-D integration acceptance, disposable localhost PostgreSQL only.
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
owner,alice,bob,viewer,admin,platform=[Client(x) for x in ('owner','alice','bob','viewer','tech-admin','platform-operator')];anon=Client()
today=datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).date();year=today.year
for path in ('/automation','/workspace/catalog','/workspace/object-policies','/workspace/notices','/migrations/templates','/pilot/readiness','/platform/p7/plans'):anon.req(path,status=401)
check('All internal P7 entry points require authentication')
for client in (alice,viewer):
 for path in ('/automation','/migrations/templates','/workspace/object-policies','/workspace/public-forms','/pilot/readiness'):client.req(path,status=403)
admin.req('/workspace/objects/ORDER',status=403);admin.req('/pilot/readiness',status=403)
check('Sales, viewers and technical administrators retain distinct permissions')
cat=owner.req('/workspace/catalog');check('Canonical catalog exposes fourteen authorized business objects',len(cat['objects'])==14)
fixtures={}
for typ in ('ORDER','INVOICE','EXPENSE','TARGET','COMMISSION','WEEKLY','PRODUCT','QUOTE','CONTRACT','RECEIPT','TICKET'):
 rows=owner.req('/workspace/objects/'+typ+'?year='+str(year))['items']
 if rows:fixtures[typ]=rows[0]
check('Extended lists reuse real order, finance, performance and service records',all(t in fixtures for t in ('ORDER','INVOICE','TARGET','COMMISSION','WEEKLY','PRODUCT','QUOTE','CONTRACT','RECEIPT','TICKET')))
order=fixtures['ORDER'];product=fixtures['PRODUCT']
raw=alice.req('/workspace/objects/ORDER/'+order['id']);check('Sales detail omits private costs',raw.get('costCents') is None and all(x.get('costCents') is None for x in raw.get('lines',[])))
viewer.req('/workspace/objects/COMMISSION/'+fixtures['COMMISSION']['id'],status=(403,404));check('Commission detail cannot bypass member privacy')
for typ in ('INVOICE','COMMISSION','WEEKLY'):
 row=fixtures[typ]
 if row.get('status') in ('ISSUED','LOCKED','APPROVED'):
  owner.req(f'/workspace/objects/{typ}/{row["id"]}/fields','PUT',{'values':{},'versions':{},'expectedRecordVersion':row['version']},status=403)
check('Finalized invoices, commissions and weekly reports keep extensions read-only')
try:
 # Independent field permissions plus optimistic versions.
 field_body={'entityType':'ORDER','fieldKey':'p7_contract_tag','label':'试点标签','dataType':'TEXT','required':True,'sectionName':'试点信息','position':10,'options':[],'readRoles':['SALES_MANAGER','SALES'],'writeRoles':['SALES_MANAGER'],'status':'ACTIVE','expectedVersion':0}
 f=owner.req('/configuration/fields','POST',field_body);fid=f['id']
 v={'values':{fid:'已核对'},'versions':{fid:-1},'expectedRecordVersion':order['version']}
 owner.req(f'/workspace/objects/ORDER/{order["id"]}/fields','PUT',{'values':{},'versions':{},'expectedRecordVersion':order['version']},status=400)
 saved=owner.req(f'/workspace/objects/ORDER/{order["id"]}/fields','PUT',v)
 check('Required fields validate and values persist',saved['values'][0]['value']=='已核对')
 owner.req(f'/workspace/objects/ORDER/{order["id"]}/fields','PUT',v,status=409)
 alice.req(f'/workspace/objects/ORDER/{order["id"]}/fields','PUT',dict(v,versions={fid:0}),status=403)
 check('Field editing enforces independent write roles and lost-update protection')
 secret=owner.req('/configuration/fields','POST',dict(field_body,entityType='CUSTOMER',fieldKey='p7_private',label='管理备注',required=False,readRoles=['SALES_MANAGER'],writeRoles=['SALES_MANAGER']))
 customer=owner.req('/workspace/objects/CUSTOMER/'+uid(301))
 owner.req('/workspace/objects/CUSTOMER/'+uid(301)+'/fields','PUT',{'values':{secret['id']:'仅经理可见'},'versions':{secret['id']:-1},'expectedRecordVersion':customer['version']})
 visible=alice.req('/workspace/objects/CUSTOMER/'+uid(301)+'/fields')
 check('Hidden fields never expose values through the extension API',not any(x['fieldId']==secret['id'] for x in visible['values']))
 legacy=alice.req('/customers/'+uid(301)+'/extensions')
 if isinstance(legacy,dict) and 'values' in legacy:assert secret['id'] not in json.dumps(legacy['values'])
 check('Legacy extension reads also filter private field values')
 owner.switch(2)
 for typ in ('ORDER','INVOICE','COMMISSION'):
  owner.req('/workspace/objects/'+typ+'/'+fixtures[typ]['id'],status=404)
 owner.switch(1);check('New object routes maintain cross-enterprise isolation')
except Exception as error:
 import traceback
 failures.append({'section':'Independent field permissions plus optimistic versions.','error':str(error)});traceback.print_exc()
try:
 # Automation revisions, idempotency, source scope, rollback and finite retries.
 def rule(name,typ='CUSTOMER',trigger='MANUAL',conditions=None,actions=None,member=200):
  body={'name':name,'entityType':typ,'triggerType':trigger,'ownerMemberId':uid(member),'definition':{'conditions':conditions or [],'actions':actions or [{'type':'NOTIFY','value':name,'memberId':uid(200),'dueDays':0}],'scheduleHour':0},'expectedVersion':0}
  q=owner.req('/automation/rules','POST',body);return owner.req('/automation/rules/'+q['id']+'/publish','POST',{'expectedVersion':q['version']})
 def execute(q,record=uid(301),key=None):return owner.req('/automation/rules/'+q['id']+'/execute','POST',{'recordId':record,'expectedVersion':q['version']},key=key)
 q=rule('P7手动提醒');key=str(uuid.uuid4());a=execute(q,key=key);same=execute(q,key=key)
 check('Manual automation executes once across retries',a['status']=='SUCCESS' and a['id']==same['id'] and a['attempts']==1)
 owner.req('/automation/rules/'+q['id']+'/execute','POST',{'recordId':uid(302),'expectedVersion':q['version']},key=key,status=409)
 check('Reusing a manual request ID for a different record is rejected')
 check('Published automation versions are archived',len(owner.req('/automation/rules/'+q['id']+'/revisions')['items'])==1)
 paused=owner.req('/automation/rules/'+q['id']+'/pause','POST',{'expectedVersion':q['version']})
 owner.req('/automation/rules/'+q['id']+'/execute','POST',{'recordId':uid(301),'expectedVersion':paused['version']},status=409)
 check('Paused rules reject new executions')
 q=rule('P7条件不匹配',conditions=[{'field':'level','operator':'EQ','value':'NEVER'}]);check('Unmatched conditions are recorded as skipped',execute(q)['status']=='SKIPPED')
 q=rule('P7原子回滚',actions=[{'type':'FILL_CUSTOMER_LEVEL','value':'C','memberId':None,'dueDays':0},{'type':'NOTIFY','value':'不可见客户提醒','memberId':uid(201),'dueDays':0}])
 before=owner.req('/workspace/objects/CUSTOMER/'+uid(302));run=execute(q,uid(302));after=owner.req('/workspace/objects/CUSTOMER/'+uid(302))
 check('Later action failure rolls back earlier customer edits',run['status']=='BLOCKED' and before['level']==after['level'] and before['version']==after['version'])
 for n in range(4):run=owner.req('/automation/runs/'+run['id']+'/retry','POST')
 owner.req('/automation/runs/'+run['id']+'/retry','POST',status=409)
 check('Failure retries are auditable and capped at five attempts',run['attempts']==5 and len(run['attemptHistory'])==5)
 q=rule('P7事件触发',typ='PRODUCT',trigger='CONDITION',conditions=[{'field':'status','operator':'EQ','value':'ACTIVE'}]);before=int(sql(f"SELECT count(*) FROM crm_automation_run WHERE rule_id='{q['id']}'"))
 product_values={'产品编码':'P7-EVENT','产品名称':'P7事件服务','类别':'服务','单位':'次','售价（元）':'200.50','成本（元）':'100','税率（%）':'6','产品分类':'试点','说明':'隔离测试'}
 newproduct=owner.req('/workspace/objects/PRODUCT','POST',{'values':product_values});owner.req('/automation/run-due','POST');owner.req('/automation/run-due','POST')
 check('Business change events execute once and do not recurse',int(sql(f"SELECT count(*) FROM crm_automation_run WHERE rule_id='{q['id']}' AND status='SUCCESS'"))==before+1)
 q=rule('P7每日定时',typ='PRODUCT',trigger='SCHEDULE',conditions=[{'field':'amountCents','operator':'GTE','value':'20000'}]);owner.req('/automation/run-due','POST');count=int(sql(f"SELECT count(*) FROM crm_automation_run WHERE rule_id='{q['id']}'"));owner.req('/automation/run-due','POST')
 check('Daily schedules deduplicate per record and day',count>0 and int(sql(f"SELECT count(*) FROM crm_automation_run WHERE rule_id='{q['id']}'"))==count)
 # Invalid scheduled owner must not starve other rules.
 qbad=rule('P7授权变更定时',typ='PRODUCT',trigger='SCHEDULE');sql(f"UPDATE crm_automation_rule SET owner_auth_version=owner_auth_version+1 WHERE id='{qbad['id']}'")
 owner.req('/automation/run-due','POST');check('Changed authorization prevents scheduled business execution',int(sql(f"SELECT count(*) FROM crm_automation_run WHERE rule_id='{qbad['id']}' AND status='SUCCESS'"))==0)
 notices=owner.req('/workspace/notices');notice=notices['items'][0];owner.req('/workspace/notices/'+notice['id']+'/read','POST');alice.req('/workspace/notices/'+notice['id']+'/read','POST',status=404)
 check('Inbox entries and read state are scoped to the recipient')
 qfill=rule('P7填充和待办',actions=[{'type':'FILL_CUSTOMER_LEVEL','value':'B','memberId':None,'dueDays':0},{'type':'CREATE_TASK','value':'P7规则待办','memberId':uid(200),'dueDays':1}])
 check('Customer fill and follow-up task creation use the original business services',execute(qfill)['status']=='SUCCESS' and owner.req('/workspace/objects/CUSTOMER/'+uid(301))['level']=='B')
 lead=owner.req('/workspace/objects/LEAD','POST',{'values':{'联系人':'自动分配联系人','公司名称':'虚构自动化企业','行业':'软件','来源':'验收'}})
 qpool=rule('P7分配回收',typ='LEAD',actions=[{'type':'ASSIGN','value':'','memberId':uid(201),'dueDays':0},{'type':'RETURN_POOL','value':'','memberId':None,'dueDays':0}])
 check('Lead assignment and resource-pool return preserve canonical eligibility checks',execute(qpool,lead['id'])['status']=='SUCCESS')
 auto_quote=owner.req('/quotes','POST',{'customerId':uid(303),'title':'P7自动流程报价','validUntil':str(today+datetime.timedelta(days=30)),'notes':'隔离测试','lines':[{'productId':newproduct['id'],'quantity':1,'unitPriceCents':20050,'discountBps':0,'description':''}],'expectedVersion':0})
 qsubmit=rule('P7发起独立审批',typ='QUOTE',actions=[{'type':'SUBMIT_QUOTE','value':'','memberId':uid(202),'dueDays':0}])
 check('Automation can submit a quote for independent approval',execute(qsubmit,auto_quote['id'])['status']=='SUCCESS')
 auto_quote=owner.req('/workspace/objects/QUOTE/'+auto_quote['id']);auto_quote=bob.req('/quotes/'+auto_quote['id']+'/approval/approve','POST',{'expectedVersion':auto_quote['version'],'approvalId':auto_quote['approval']['id'],'reason':'隔离人工审批'})
 qorder=rule('P7批准报价转订单',typ='QUOTE',actions=[{'type':'CREATE_ORDER','value':'','memberId':None,'dueDays':0}])
 check('Only an approved quote can produce a draft order through automation',execute(qorder,auto_quote['id'])['status']=='SUCCESS')
except Exception as error:
 import traceback
 failures.append({'section':'Automation revisions, idempotency, source scope, rollback and finite retries.','error':str(error)});traceback.print_exc()
try:
 # CSV templates, preview, revalidation, all-or-nothing rollback.
 templates=owner.req('/migrations/templates')['items'];check('Seven controlled migration templates are available',len(templates)==7)
 def preview(typ,items,name='P7迁移.csv'):
  columns=next(t['columns'] for t in templates if t['id']==typ);buf=io.StringIO();w=csv.writer(buf);w.writerow(columns)
  for item in items:w.writerow([item.get(c,'') for c in columns])
  return owner.req('/migrations/preview','POST',{'entityType':typ,'fileName':name,'csvBase64':base64.b64encode(buf.getvalue().encode()).decode()})
 bad=preview('PRODUCT',[dict(product_values,**{'售价（元）':'不是金额'})]);check('CSV preview gives row-level actionable errors',bool(bad['rowsData'][0]['errors']))
 owner.req('/migrations/'+bad['id']+'/commit','POST',{'expectedVersion':0},status=400)
 check('Rows with preview errors cannot be committed')
 batch=preview('PRODUCT',[dict(product_values,**{'产品编码':'P7-ROLLBACK-FIRST'}),dict(product_values,**{'产品编码':'P7-ROLLBACK-DUP'})])
 owner.req('/workspace/objects/PRODUCT','POST',{'values':dict(product_values,**{'产品编码':'P7-ROLLBACK-DUP'})})
 owner.req('/migrations/'+batch['id']+'/commit','POST',{'expectedVersion':0},status=409)
 check('Batch commit rechecks current data and rolls back every earlier row',sql("SELECT count(*) FROM crm_product WHERE sku='P7-ROLLBACK-FIRST'")=='0' and owner.req('/migrations/'+batch['id'])['status']=='PREVIEW')
 batch=preview('LEAD',[{'联系人':'迁移联系人','公司名称':'P7试点企业','行业':'软件','来源':'迁移','电话':'13800000123'}]);key=str(uuid.uuid4());committed=owner.req('/migrations/'+batch['id']+'/commit','POST',{'expectedVersion':0},key=key);same=owner.req('/migrations/'+batch['id']+'/commit','POST',{'expectedVersion':0},key=key)
 check('Successful migration retries return the same created records',committed['status']=='COMMITTED' and len(committed['results'])==1 and same['results']==committed['results'])
 old=preview('LEAD',[{'联系人':'过期批次','公司名称':'虚构企业'}]);sql(f"UPDATE crm_migration_batch SET created_at=now()-interval '2 days' WHERE id='{old['id']}'");owner.req('/migrations/'+old['id']+'/commit','POST',{'expectedVersion':0},status=409)
 check('Migration confirmations expire after 24 hours')
except Exception as error:
 import traceback
 failures.append({'section':'CSV templates, preview, revalidation, all-or-nothing rollback.','error':str(error)});traceback.print_exc()
try:
 # The remaining migration templates also create domain drafts, never approved finance.
 quote=owner.req('/quotes','POST',{'customerId':uid(303),'title':'P7迁移来源报价','validUntil':str(today+datetime.timedelta(days=30)),'notes':'隔离验收','lines':[{'productId':newproduct['id'],'quantity':1,'unitPriceCents':20050,'discountBps':0,'description':''}],'expectedVersion':0})
 quote=owner.req('/quotes/'+quote['id']+'/submit','POST',{'expectedVersion':quote['version'],'approverMemberId':uid(202),'reason':''})
 quote=bob.req('/quotes/'+quote['id']+'/approval/approve','POST',{'expectedVersion':quote['version'],'approvalId':quote['approval']['id'],'reason':'独立复核'})
 def migrate_one(typ,row):
  batch=preview(typ,[row]);assert not batch['rowsData'][0]['errors'],batch['rowsData'];return owner.req('/migrations/'+batch['id']+'/commit','POST',{'expectedVersion':0})['results'][0]
 order_id=migrate_one('ORDER',{'批准报价编号':quote['quoteNumber'],'订单名称':'P7迁移订单','预计交付日期':str(today+datetime.timedelta(days=5))})
 check('Order template requires an approved quote and creates an unconfirmed draft',owner.req('/sales-orders/'+order_id)['status']=='DRAFT')
 imported_order=owner.req('/sales-orders/'+order_id);owner.req('/sales-orders/'+order_id+'/confirm','POST',{'expectedVersion':imported_order['version'],'reason':'隔离人工确认'})
 contract=owner.req('/contracts','POST',{'quoteId':quote['id'],'quoteExpectedVersion':quote['version'],'orderId':order_id,'title':'P7迁移来源合同','startDate':str(today),'endDate':str(today+datetime.timedelta(days=365)),'notes':'','plans':[{'title':'合同款','amountCents':20050,'dueDate':str(today)}]})
 contract=owner.req('/contracts/'+contract['id']+'/sign','POST',{'expectedVersion':contract['version'],'signedDate':str(today),'reason':''})
 invoice_id=migrate_one('INVOICE',{'合同编号':contract['contractNumber'],'开票标题':'P7迁移开票','购方名称':'虚构企业','金额（元）':'1.00'})
 account=owner.req('/finance/accounts')['items'][0]
 expense_id=migrate_one('EXPENSE',{'资金账户':account['name'],'收款方':'虚构供应商','支出类别':'迁移验收','金额（元）':'1.00','付款日期':str(today),'付款流水号':'P7-EXPENSE-001'})
 check('Invoice and expense migrations cannot bypass approval or post cash entries',owner.req('/finance/invoices/'+invoice_id)['status']=='DRAFT' and owner.req('/finance/expenses')['items'][0]['status']=='DRAFT')
 future=(today.replace(day=1)+datetime.timedelta(days=35)).replace(day=1)
 target_id=migrate_one('TARGET',{'目标对象':'owner','月份':str(future)[:7],'周期月数':'1','目标金额（元）':'1000'})
 weekly_id=migrate_one('WEEKLY',{'周起始日':str(today-datetime.timedelta(days=today.weekday()+14)),'工作进展':'迁移周报','本周成果':'虚构验收','风险事项':'无','下周计划':'继续'})
 check('Target and weekly templates preserve canonical scope and draft state',target_id is not None and owner.req('/workspace/objects/WEEKLY/'+weekly_id)['status']=='DRAFT')
except Exception as error:
 import traceback
 failures.append({'section':'The remaining migration templates also create domain drafts, never approved finance.','error':str(error)});traceback.print_exc()
try:
 # Signed API remains scoped, nonce protected, and source-authorized.
 k=owner.req('/integrations/keys','POST',{'name':'P7只读订单接口','ownerMemberId':uid(201),'scopes':['orders.read'],'expiresDays':1});token=k['oneTimeToken']
 def signed(typ='ORDER',record=None,nonce=None,status=200):
  body=json.dumps({'entityType':typ,'recordId':record,'page':1,'pageSize':20,'q':'','year':year},separators=(',',':')).encode();stamp=str(int(time.time()));nonce=nonce or uuid.uuid4().hex
  canonical='POST\n/api/v1/inbound/records\n'+stamp+'\n'+nonce+'\n'+hashlib.sha256(body).hexdigest();sig=hmac.new(token.split('.')[2].encode(),canonical.encode(),hashlib.sha256).hexdigest()
  return anon.req('/inbound/records','POST',body,status=status,headers={'Authorization':'Bearer '+token,'X-CRM-Timestamp':stamp,'X-CRM-Nonce':nonce,'X-CRM-Signature':sig})
 nonce=uuid.uuid4().hex;data=signed(nonce=nonce);signed(nonce=nonce,status=409);signed('INVOICE',status=403)
 check('Signed new-object reads enforce scopes and reject nonce replay',data['total']>=1)
 check('Signed source-reader still hides product costs',signed(record=order['id']).get('costCents') is None)
except Exception as error:
 import traceback
 failures.append({'section':'Signed API remains scoped, nonce protected, and source-authorized.','error':str(error)});traceback.print_exc()
try:
 # Additional object denial applies to canonical routes, fields, exports and signed reads.
 pol={'roleCode':'SALES','canRead':False,'canWrite':False,'expectedVersion':0};owner.req('/workspace/object-policies/ORDER','PUT',pol)
 for path in ('/sales-orders','/workspace/objects/ORDER','/workspace/objects/ORDER/'+order['id']+'/fields','/workspace/objects/ORDER/export'):alice.req(path,status=403)
 signed(status=403);check('Object policy denial cannot be bypassed through core, extension, export or signed routes')
 owner.req('/workspace/object-policies/ORDER','PUT',dict(pol,canRead=True,canWrite=True,expectedVersion=1))
 owner.req('/workspace/object-policies/ORDER','PUT',pol,status=409);check('Policy edits enforce optimistic versions')
except Exception as error:
 import traceback
 failures.append({'section':'Additional object denial applies to canonical routes, fields, exports and signed reads.','error':str(error)});traceback.print_exc()
try:
 # Public intake no login, fixed fields, contact consent, hidden IDs, reuse and revocation.
 f=owner.req('/workspace/public-forms','POST',{'name':'P7咨询入口','entityType':'LEAD','ownerMemberId':uid(201),'expiresDays':1});token_form=f['oneTimeToken'];meta=anon.req('/public-intake/'+token_form)
 check('Public form metadata omits internal enterprise and member identifiers',set(meta)=={'name','entityType','expiresAt','consentRequired'})
 submission={'name':'咨询联系人','companyName':'虚构咨询公司','phone':'13800000124','email':'','message':'咨询产品','consent':False,'website':''};anon.req('/public-intake/'+token_form,'POST',submission,status=400)
 submission['consent']=True;key=uuid.uuid4().hex;r=anon.req('/public-intake/'+token_form,'POST',submission,key=key);same=anon.req('/public-intake/'+token_form,'POST',submission,key=key)
 check('Consent is mandatory and repeated submissions create one lead',r==same and set(r)=={'receiptId','message'})
 anon.req('/public-intake/'+token_form,'POST',dict(submission,name='另一联系人'),key=key,status=409)
 for n in range(9):anon.req('/public-intake/'+token_form,'POST',submission)
 anon.req('/public-intake/'+token_form,'POST',submission,status=429);check('Public form rate limits and changed-payload replay are enforced')
 owner.req('/workspace/public-forms/'+f['id']+'?expectedVersion=0','DELETE');anon.req('/public-intake/'+token_form,status=404);check('Revoked links stop accepting public access')
 f=owner.req('/workspace/public-forms','POST',{'name':'P7过期入口','entityType':'LEAD','ownerMemberId':uid(201),'expiresDays':1});sql(f"UPDATE crm_public_form SET expires_at=now()-interval '1 minute' WHERE id='{f['id']}'");anon.req('/public-intake/'+f['oneTimeToken'],status=404);check('Expired public forms are inaccessible')
except Exception as error:
 import traceback
 failures.append({'section':'Public intake no login, fixed fields, contact consent, hidden IDs, reuse and revocation.','error':str(error)});traceback.print_exc()
try:
 # A configured webhook is queued, then disabled; no external delivery is requested.
 hook=owner.req('/integrations/webhooks','POST',{'name':'P7队列验收','url':'https://example.com/p7-acceptance','ownerMemberId':uid(200),'events':['weekly.created'],'active':True,'expectedVersion':0})
 weekly_date=today-datetime.timedelta(days=today.weekday())-datetime.timedelta(days=7)
 owner.req('/workspace/objects/WEEKLY','POST',{'values':{'周起始日':str(weekly_date),'工作进展':'队列验收','本周成果':'虚构数据','风险事项':'无','下周计划':'继续'}})
 check('New object webhook events enter the durable metadata-only queue',int(sql("SELECT count(*) FROM crm_webhook_outbox WHERE event_type='weekly.created'"))>0)
 hook_id=hook.get('webhook',hook.get('subscription',hook.get('hook',hook))).get('id')
 if hook_id:owner.req('/integrations/webhooks/'+hook_id,'PUT',{'name':'P7队列验收','url':'https://example.com/p7-acceptance','ownerMemberId':uid(200),'events':['weekly.created'],'active':False,'expectedVersion':0})
except Exception as error:
 import traceback
 failures.append({'section':'A configured webhook is queued, then disabled; no external delivery is requested.','error':str(error)});traceback.print_exc()
try:
 # Plan controls, pilot snapshots, expired tenant writes and physical RLS.
 owner.req('/platform/p7/plans',status=403);plans=platform.req('/platform/p7/plans')['items'];plan=next(p for p in plans if p['id']==sql("SELECT plan_id FROM sys_tenant WHERE id='"+uid(1)+"'"))
 body={k:plan[k] for k in ('automationEnabled','automationRuleLimit','automationMonthlyLimit','workspaceEnabled','publicFormLimit','publicSubmissionMonthlyLimit','migrationEnabled','migrationMonthlyRowLimit')};body['expectedVersion']=plan['version'];body['migrationMonthlyRowLimit']=0
 updated=platform.req('/platform/p7/plans/'+plan['id'],'PUT',body);newplan=next(x for x in updated['items'] if x['id']==plan['id'])
 batch=preview('LEAD',[{'联系人':'额度测试','公司名称':'虚构','行业':'软件','来源':'验收'}]);owner.req('/migrations/'+batch['id']+'/commit','POST',{'expectedVersion':0},status=409)
 body['expectedVersion']=newplan['version'];body['migrationMonthlyRowLimit']=plan['migrationMonthlyRowLimit'];body['automationEnabled']=False;updated=platform.req('/platform/p7/plans/'+plan['id'],'PUT',body);owner.req('/automation/run-due','POST',status=403)
 body['expectedVersion']=next(x for x in updated['items'] if x['id']==plan['id'])['version'];body['automationEnabled']=True;platform.req('/platform/p7/plans/'+plan['id'],'PUT',body)
 check('Product operator plan quotas and feature switches take effect immediately')
 readiness=owner.req('/pilot/readiness');archived=owner.req('/pilot/checks','POST',{'notes':'隔离数据的软件验收，不代表真实客户签收'})
 check('Pilot readiness distinguishes software checks from real customer acceptance',readiness['externalTrialRequired'] is True and readiness['softwareAcceptanceSeparate'] is True and archived['id'] in [x['id'] for x in owner.req('/pilot/history')['items']])
 owner.switch(3);owner.req('/migrations/preview','POST',{'entityType':'LEAD','fileName':'x.csv','csvBase64':'eA=='},status=403);owner.req('/workspace/objects/LEAD','POST',{'values':{'联系人':'过期','公司名称':'虚构'}},status=403);owner.switch(1)
 check('Expired subscriptions reject all new writes')
 check('All new tenant tables force database row isolation',sql("SELECT count(*) FROM pg_class WHERE relname IN('crm_automation_rule','crm_automation_revision','crm_automation_event','crm_automation_run','crm_automation_attempt','crm_object_policy','crm_workspace_attachment','crm_workspace_notice','crm_public_form','crm_public_submission','crm_migration_batch','crm_pilot_run') AND relrowsecurity AND relforcerowsecurity")=='12')
except Exception as error:
 import traceback
 failures.append({'section':'Plan controls, pilot snapshots, expired tenant writes and physical RLS.','error':str(error)});traceback.print_exc()
lat=[]
for n in range(10):
 start=time.perf_counter();owner.req('/workspace/objects/ORDER');lat.append(round((time.perf_counter()-start)*1000,2))
result={'stage':'P7 B-D','passed':len(checks),'checks':checks,'failures':failures,'database':'disposable PostgreSQL 18','scope':'synthetic data only','latencyMs':{'samples':len(lat),'median':statistics.median(lat),'max':max(lat),'note':'Single CI client, small synthetic dataset; not a production capacity benchmark'}}
print(json.dumps(result,ensure_ascii=False,indent=2));pathlib.Path(os.environ.get('CRM_ACCEPTANCE_RESULT','p7-acceptance.json')).write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')

if failures:raise SystemExit('P7 acceptance failed in '+str(len(failures))+' independent sections')
