"""P6 acceptance: disposable local PostgreSQL and synthetic seed accounts only."""
import os,json,uuid,datetime,urllib.request,urllib.error,http.cookiejar,urllib.parse,pathlib
BASE=os.environ.get('CRM_ACCEPTANCE_URL','http://127.0.0.1:8080').rstrip('/')
assert os.environ.get('CRM_ISOLATED_ACCEPTANCE')=='true'
assert urllib.parse.urlparse(BASE).hostname in ('127.0.0.1','localhost'), 'Only a disposable local API is allowed'
PASSWORD=os.environ['CRM_TEST_PASSWORD'];checks=[]
def uid(n):return '00000000-0000-4000-8000-'+str(n).zfill(12)
today=datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).date();month=today.replace(day=1);previous=(month-datetime.timedelta(days=1)).replace(day=1);cash_date=previous+datetime.timedelta(days=5);monday=today-datetime.timedelta(days=today.weekday())
def check(name,ok=True):
 if not ok:raise AssertionError(name)
 checks.append(name)
class Client:
 def __init__(self,name=None):
  self.opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()));self.csrf='';self.tenant=''
  if name:
   data=self.req('/auth/login','POST',{'username':name,'password':PASSWORD});self.csrf=data['csrfToken']
   if name!='platform-operator':self.switch(1)
 def switch(self,n):
  self.tenant='';d=self.req('/auth/active-tenant','POST',{'tenantId':uid(n)});self.csrf=d['csrfToken'];self.tenant=uid(n)
 def req(self,path,method='GET',body=None,status=200,key=None):
  headers={'Content-Type':'application/json'}
  if self.csrf:headers['X-CSRF-Token']=self.csrf
  if self.tenant:headers['X-Workspace-ID']=self.tenant
  if method=='POST':headers['Idempotency-Key']=key or str(uuid.uuid4())
  raw=None if body is None else json.dumps(body).encode();req=urllib.request.Request(BASE+'/api/v1'+path,data=raw,headers=headers,method=method)
  try:
   with self.opener.open(req,timeout=40) as r:code=r.status;content=r.read()
  except urllib.error.HTTPError as e:code=e.code;content=e.read()
  result=json.loads(content) if content else None
  if isinstance(status,tuple):valid=code in status
  else:valid=code==status
  if not valid:raise AssertionError(f'{method} {path}: expected {status}, got {code}: {result}')
  return result
owner,alice,bob,viewer,admin,platform=[Client(x) for x in ('owner','alice','bob','viewer','tech-admin','platform-operator')];anon=Client()
import xml.etree.ElementTree as ET
expected_version=ET.parse(pathlib.Path(__file__).parents[1]/'pom.xml').getroot().find('{http://maven.apache.org/POM/4.0.0}version').text
check('Health reports the packaged Maven version',anon.req('/health')['version']==expected_version)
for path in ('/sales-orders','/finance/accounts','/finance/invoices','/performance/targets?year='+str(today.year),'/service/statistics?from='+str(month)+'&to='+str(today)):
 anon.req(path,status=401)
check('All internal P6 endpoints require login')
alice.req('/finance/accounts',status=403);admin.req('/performance/targets?year='+str(today.year),status=403)
check('Sales and technical administrators cannot read the enterprise cash ledger or unauthorized performance records')
account=owner.req('/finance/accounts','POST',{'name':'P6验收资金账户','accountReference':'虚构账户','openingCents':0,'openingDate':str(previous)})
product=owner.req('/products','POST',{'sku':'P6-ACCEPTANCE','name':'P6验收服务','kind':'SERVICE','unit':'次','priceCents':10000,'costCents':5000,'taxRateBps':1300,'category':'验收','description':'虚构测试','status':'ACTIVE','expectedVersion':0})
quote=alice.req('/quotes','POST',{'customerId':uid(301),'title':'P6全流程报价','validUntil':str(today+datetime.timedelta(days=30)),'notes':'虚构验收','lines':[{'productId':product['id'],'quantity':10,'unitPriceCents':10000,'discountBps':0,'description':''}],'expectedVersion':0})
quote=alice.req('/quotes/'+quote['id']+'/submit','POST',{'expectedVersion':quote['version'],'approverMemberId':uid(200),'reason':''})
quote=owner.req('/quotes/'+quote['id']+'/approval/approve','POST',{'expectedVersion':quote['version'],'approvalId':quote['approval']['id'],'reason':'核验通过'})
body={'quoteId':quote['id'],'quoteExpectedVersion':quote['version'],'title':'P6销售订单','expectedDeliveryDate':str(today+datetime.timedelta(days=10)),'notes':''};key=str(uuid.uuid4())
order=alice.req('/sales-orders','POST',body,key=key);same=alice.req('/sales-orders','POST',body,key=key);check('Retrying an order creation returns the same order',order['id']==same['id'])
alice.req('/sales-orders','POST',body,status=409)
order=alice.req('/sales-orders/'+order['id']+'/confirm','POST',{'expectedVersion':order['version'],'reason':''})
order=alice.req('/sales-orders/'+order['id']+'/fulfillment','PUT',{'expectedVersion':order['version'],'lines':[{'lineId':order['lines'][0]['id'],'deliveredQuantity':5}]})
for quantity in (4,11):alice.req('/sales-orders/'+order['id']+'/fulfillment','PUT',{'expectedVersion':order['version'],'lines':[{'lineId':order['lines'][0]['id'],'deliveredQuantity':quantity}]},status=(400,409))
check('Order fulfillment rejects decreases and excess delivery',order['status']=='IN_DELIVERY')
contract=alice.req('/contracts','POST',{'quoteId':quote['id'],'quoteExpectedVersion':quote['version'],'orderId':order['id'],'title':'P6订单关联合同','startDate':str(previous),'endDate':str(today.replace(year=today.year+1)),'notes':'','plans':[{'title':'合同款','amountCents':100000,'dueDate':str(today)}]})
contract=alice.req('/contracts/'+contract['id']+'/sign','POST',{'expectedVersion':contract['version'],'signedDate':str(previous),'reason':''})
check('Approved quote, order, contract and collection plan retain the same total',contract['totalCents']==order['totalCents']==100000)
receipt_body={'contractId':contract['id'],'planId':contract['plans'][0]['id'],'amountCents':80000,'receivedDate':str(cash_date),'paymentReference':'P6-RC-001','payerName':'验收客户','bankName':'虚构银行','notes':'','proofAttachmentId':None,'correctionOfReceiptId':None,'expectedVersion':0,'fundAccountId':account['id']}
receipt=alice.req('/receipts','POST',receipt_body);alice.req('/receipts/'+receipt['id']+'/confirm','POST',{'expectedVersion':receipt['version'],'reason':'','proofAttachmentId':None},status=403)
confirm={'expectedVersion':receipt['version'],'reason':'银行记录核验','proofAttachmentId':None};key=str(uuid.uuid4());receipt=owner.req('/receipts/'+receipt['id']+'/confirm','POST',confirm,key=key);owner.req('/receipts/'+receipt['id']+'/confirm','POST',confirm,key=key)
check('Cash confirmation enforces separation of duties and posts once',owner.req('/finance/accounts')['items'][0]['balanceCents']==80000)
invoice=alice.req('/finance/invoices','POST',{'contractId':contract['id'],'title':'第一期开票','buyerName':'虚构客户','taxNumber':'','amountCents':60000,'notes':''})
invoice=alice.req('/finance/invoices/'+invoice['id']+'/submit','POST',{'expectedVersion':invoice['version']})
invoice=owner.req('/finance/invoices/'+invoice['id']+'/approve','POST',{'expectedVersion':invoice['version'],'reason':'核验通过'})
invoice=owner.req('/finance/invoices/'+invoice['id']+'/issue','POST',{'expectedVersion':invoice['version'],'reference':'P6-IV-001','businessDate':str(today)})
alice.req('/finance/invoices','POST',{'contractId':contract['id'],'title':'超额申请','buyerName':'虚构客户','amountCents':50000},status=409)
invoice=owner.req('/finance/invoices/'+invoice['id']+'/red','POST',{'expectedVersion':invoice['version'],'reference':'P6-RED-001','businessDate':str(today),'amountCents':10000,'reason':'部分红冲'})
alice.req('/finance/invoices','POST',{'contractId':contract['id'],'title':'第二期开票','buyerName':'虚构客户','amountCents':50000})
check('Invoices reserve contract capacity, permit partial red invoices and multiple applications',invoice['redCents']==10000)
receipt=owner.req('/receipts/'+receipt['id']+'/reverse','POST',{'expectedVersion':receipt['version'],'amountCents':20000,'reversalDate':str(cash_date),'reason':'部分到账冲正','proofAttachmentId':None})
expense=alice.req('/finance/expenses','POST',{'accountId':account['id'],'payee':'虚构供应商','category':'差旅','amountCents':5000,'paymentDate':str(today),'paymentReference':'P6-EX-001','notes':''})
alice.req('/finance/expenses/'+expense['id']+'/confirm','POST',{'expectedVersion':expense['version']},status=403)
expense=owner.req('/finance/expenses/'+expense['id']+'/confirm','POST',{'expectedVersion':expense['version'],'reason':'复核支出'})
check('Confirmed expenses reduce the ledger balance',owner.req('/finance/accounts')['items'][0]['balanceCents']==55000)
expense=owner.req('/finance/expenses/'+expense['id']+'/reverse','POST',{'expectedVersion':expense['version'],'businessDate':str(today),'reason':'撤销虚构付款'})
check('Expense reversals add an opposite entry without deleting the original',owner.req('/finance/accounts')['items'][0]['balanceCents']==60000)
balances=alice.req('/finance/orders/'+order['id']+'/balances');check('Order invoice and cash balances reconcile independently',balances['invoicedCents']==50000 and balances['receivedCents']==60000 and balances['uninvoicedCents']==50000 and balances['unreceivedCents']==40000)
check('Ledger returns source entries and running balances',len(owner.req('/finance/accounts/'+account['id']+'/entries')['items'])==5)
# Performance uses the same confirmed cash less reversal source.
annual=owner.req('/performance/targets','POST',{'memberId':uid(201),'departmentId':None,'periodStart':str(previous.replace(month=1)),'periodMonths':12,'targetCents':1000000,'expectedVersion':0})
target=owner.req('/performance/targets','POST',{'memberId':uid(201),'periodStart':str(previous),'periodMonths':1,'targetCents':100000,'expectedVersion':0})
actual=next(x for x in alice.req('/performance/targets?year='+str(previous.year))['items'] if x['id']==target['id']);check('Targets count confirmed net cash, including partial reversals',actual['actualCents']==60000)
owner.req('/performance/targets/'+annual['id'],'PUT',{'memberId':uid(201),'periodStart':str(previous.replace(month=1)),'periodMonths':12,'targetCents':50000,'expectedVersion':0},status=409)
rule=owner.req('/performance/rules','POST',{'name':'P6累进阶梯','tiers':[{'thresholdCents':0,'rateBps':300},{'thresholdCents':50000,'rateBps':500}],'active':True,'expectedVersion':0})
calc_body={'memberId':uid(201),'ruleId':rule['id'],'periodStart':str(previous),'expectedVersion':0};commission=owner.req('/performance/commissions/calculate','POST',calc_body)
check('Progressive tiers apply only to each tier slice',commission['baseCents']==60000 and commission['commissionCents']==2000)
check('Read-only analytics access does not expose other employees commission',not viewer.req('/performance/commissions?period='+str(previous))['items'])
viewer.req('/performance/history/COMMISSION/'+commission['id'],status=404)
owner.req('/performance/commissions/'+commission['id']+'/review','POST',{'expectedVersion':commission['version']},status=403)
commission=bob.req('/performance/commissions/'+commission['id']+'/review','POST',{'expectedVersion':commission['version'],'reason':'独立复核'})
extra_body={**receipt_body,'amountCents':1000,'paymentReference':'P6-RC-SOURCE-CHANGE'}
extra=alice.req('/receipts','POST',extra_body)
extra=owner.req('/receipts/'+extra['id']+'/confirm','POST',{'expectedVersion':extra['version'],'reason':'来源变化验收'})
extra=owner.req('/receipts/'+extra['id']+'/reverse','POST',{'expectedVersion':extra['version'],'amountCents':1000,'reversalDate':str(cash_date),'reason':'来源变化验收冲正'})
owner.req('/performance/commissions/'+commission['id']+'/lock','POST',{'expectedVersion':commission['version'],'reason':'旧快照锁定'},status=409)
check('A changed cash source invalidates review even when the net cash total is unchanged')
calc_body['expectedVersion']=commission['version'];commission=owner.req('/performance/commissions/calculate','POST',calc_body)
commission=bob.req('/performance/commissions/'+commission['id']+'/review','POST',{'expectedVersion':commission['version'],'reason':'重算后独立复核'})
commission=owner.req('/performance/commissions/'+commission['id']+'/lock','POST',{'expectedVersion':commission['version'],'reason':'月结锁定'})
calc_body['expectedVersion']=commission['version'];owner.req('/performance/commissions/calculate','POST',calc_body,status=409)
commission=owner.req('/performance/commissions/'+commission['id']+'/adjust','POST',{'expectedVersion':commission['version'],'deltaCents':100,'reason':'追加核算调整'})
check('Locked commissions cannot be recalculated; adjustments preserve the original amount',commission['status']=='LOCKED' and commission['commissionCents']==2000 and commission['adjustmentCents']==100)
weekly_body={'weekStart':str(monday),'progress':'已完成客户方案沟通','achievements':'确认验收订单','risks':'交期需要跟进','nextActions':'确认排产与回款计划','expectedVersion':0};weekly=alice.req('/performance/weeklies','POST',weekly_body)
weekly=alice.req('/performance/weeklies/'+weekly['id']+'/submit','POST',{'expectedVersion':weekly['version']})
alice.req('/performance/weeklies/'+weekly['id']+'/approve','POST',{'expectedVersion':weekly['version']},status=403)
weekly=owner.req('/performance/weeklies/'+weekly['id']+'/reject','POST',{'expectedVersion':weekly['version'],'reason':'补充交期风险'})
weekly_body['risks']='已补充交期及负责人';weekly_body['expectedVersion']=weekly['version'];weekly=alice.req('/performance/weeklies/'+weekly['id'],'PUT',weekly_body)
weekly=alice.req('/performance/weeklies/'+weekly['id']+'/submit','POST',{'expectedVersion':weekly['version']});weekly=owner.req('/performance/weeklies/'+weekly['id']+'/approve','POST',{'expectedVersion':weekly['version'],'reason':'批准归档'})
weekly_body['expectedVersion']=weekly['version'];alice.req('/performance/weeklies/'+weekly['id'],'PUT',weekly_body,status=403)
check('Structured weekly reports support submit, return, edit and approval without overwriting approved reports',weekly['status']=='APPROVED')
# Service acceptance, dispatch, SLA pause/resume and one-time public feedback.
check('Read-only members cannot be selected as service assignees',all(x['id']!=uid(203) for x in owner.req('/tickets/owners')))
ticket=owner.req('/tickets','POST',{'title':'P6虚构客诉','description':'仅用于隔离验收','customerId':uid(301),'assigneeMemberId':uid(201),'priority':'NORMAL','slaPolicyId':None,'requesterName':'','requesterEmail':'','expectedVersion':0,'typeCode':'COMPLAINT'})
owner.req('/tickets/'+ticket['id']+'/start','POST',{'expectedVersion':ticket['version'],'reason':'代接单'},status=403)
ticket=alice.req('/tickets/'+ticket['id']+'/start','POST',{'expectedVersion':ticket['version'],'reason':'本人接单'})
alice.req('/tickets/'+ticket['id']+'/wait','POST',{'expectedVersion':ticket['version'],'reason':'未回复先等待'},status=409)
ticket=alice.req('/tickets/'+ticket['id']+'/comments','POST',{'expectedVersion':ticket['version'],'body':'已受理，请补充问题资料','kind':'PUBLIC_REPLY'})
ticket=alice.req('/tickets/'+ticket['id']+'/wait','POST',{'expectedVersion':ticket['version'],'reason':'等待客户补充资料'});check('Waiting on a customer pauses the configured SLA',ticket['slaState']=='PAUSED')
ticket=alice.req('/tickets/'+ticket['id']+'/resume','POST',{'expectedVersion':ticket['version'],'reason':'资料已收到'})
ticket=alice.req('/tickets/'+ticket['id']+'/comments','POST',{'expectedVersion':ticket['version'],'body':'问题已定位，提供解决方案','kind':'PUBLIC_REPLY'})
ticket=alice.req('/tickets/'+ticket['id']+'/resolve','POST',{'expectedVersion':ticket['version'],'reason':'客户确认已解决'})
link=owner.req('/service/tickets/'+ticket['id']+'/feedback-link','POST',{'expectedVersion':ticket['version'],'reason':'邀请评价'});token=urllib.parse.parse_qs(urllib.parse.urlparse(link['path']).query)['service-feedback'][0]
anon.req('/service-feedback/'+token);anon.req('/service-feedback/'+token,'POST',{'score':5,'comment':'虚构满意评价'});anon.req('/service-feedback/'+token,'POST',{'score':4,'comment':''},status=409)
check('Public evaluation links reveal only the ticket reference and accept one rating',set(anon.req('/service-feedback/'+token))=={'ticketNumber','expiresAt','submitted'})
report=owner.req('/service/statistics?from='+str(month)+'&to='+str(today));check('Service statistics include satisfaction and response/service durations',any(float(x['satisfaction'] or 0)==5 and x['responseMinutes'] is not None for x in report['items']))
ticket=owner.req('/tickets/'+ticket['id']+'/reopen','POST',{'expectedVersion':ticket['version'],'reason':'复现后复查'});anon.req('/service-feedback/'+token,status=404)
ticket=owner.req('/service/tickets/'+ticket['id']+'/dispatch','POST',{'assigneeMemberId':uid(202),'expectedVersion':ticket['version'],'reason':'转派复查'});ticket=bob.req('/tickets/'+ticket['id']+'/start','POST',{'expectedVersion':ticket['version'],'reason':'接单复查'})
check('Reopening invalidates old feedback links and transfer requires the new assignee to accept',ticket['status']=='IN_PROGRESS')
check('P6 action histories remain readable',len(owner.req('/finance/history/INVOICE/'+invoice['id'])['items'])>=5 and len(owner.req('/performance/history/COMMISSION/'+commission['id'])['items'])==6)
viewer.req('/finance/invoices','POST',{'contractId':contract['id'],'title':'越权','buyerName':'虚构','amountCents':1},status=403)
viewer.req('/performance/weeklies','POST',weekly_body,status=403)
owner.switch(2);owner.req('/sales-orders/'+order['id'],status=404);owner.req('/finance/invoices/'+invoice['id'],status=404);owner.req('/performance/history/COMMISSION/'+commission['id'],status=404);owner.req('/service/tickets/'+ticket['id'],status=404)
check('Orders, invoices, commissions and tickets enforce cross-enterprise isolation')
owner.switch(3);owner.req('/performance/targets','POST',{'memberId':uid(220),'periodStart':str(month),'periodMonths':1,'targetCents':1000},status=403)
check('Expired enterprises and viewers cannot write P6 business data')
result={'stage':'P6','passed':len(checks),'checks':checks,'database':'disposable PostgreSQL','scope':'synthetic test data only'}
print(json.dumps(result,ensure_ascii=False,indent=2))
if os.environ.get('CRM_ACCEPTANCE_RESULT'):pathlib.Path(os.environ['CRM_ACCEPTANCE_RESULT']).write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
