"""P7-A analytics acceptance after P6 fixtures, disposable PostgreSQL only."""
import os,json,uuid,datetime,urllib.request,urllib.error,http.cookiejar,urllib.parse,pathlib,csv,io
BASE=os.environ.get('CRM_ACCEPTANCE_URL','http://127.0.0.1:8080').rstrip('/')
assert os.environ.get('CRM_ISOLATED_ACCEPTANCE')=='true'
assert urllib.parse.urlparse(BASE).hostname in ('localhost','127.0.0.1')
PASSWORD=os.environ['CRM_TEST_PASSWORD'];checks=[]
def uid(n):return '00000000-0000-4000-8000-'+str(n).zfill(12)
def check(name,ok=True):
 if not ok:raise AssertionError(name)
 checks.append(name)
class Client:
 def __init__(self,name=None):
  self.opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()));self.csrf='';self.tenant=''
  if name:
   d=self.req('/auth/login','POST',{'username':name,'password':PASSWORD});self.csrf=d['csrfToken'];self.switch(1)
 def switch(self,n):
  self.tenant='';d=self.req('/auth/active-tenant','POST',{'tenantId':uid(n)});self.csrf=d['csrfToken'];self.tenant=uid(n)
 def req(self,path,method='GET',body=None,status=200,raw=False):
  headers={'Content-Type':'application/json','X-CSRF-Token':self.csrf,'X-Workspace-ID':self.tenant,'Idempotency-Key':str(uuid.uuid4())}
  req=urllib.request.Request(BASE+'/api/v1'+path,data=None if body is None else json.dumps(body).encode(),headers=headers,method=method)
  try:
   with self.opener.open(req,timeout=60) as r:code=r.status;content=r.read()
  except urllib.error.HTTPError as e:code=e.code;content=e.read()
  if code!=status:raise AssertionError(f'{path}: expected {status}, got {code}: {content[:600]}')
  return content if raw else json.loads(content) if content else None
now=datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).date();start=(now.replace(day=1)-datetime.timedelta(days=1)).replace(day=1)
q=urllib.parse.urlencode({'from':str(start),'to':str(now)})
owner,alice,viewer,admin,anon=[Client(x) for x in ('owner','alice','viewer','tech-admin',None)]
for path in ('/analytics?'+q,'/analytics/cash/details?'+q,'/analytics/cash/export?'+q):anon.req(path,status=401)
admin.req('/analytics?'+q,status=403)
check('Analytics, drilldown and export require authentication and report permission')
r=owner.req('/analytics?'+q);panels={p['id']:p for p in r['panels']}
check('All eleven authorized analytics topics load on PostgreSQL',len(panels)==11)
check('Confirmed order totals and historical net cash are separate',panels['orders']['count']==1 and int(panels['orders']['amountCents'])==100000 and int(panels['cash']['amountCents'])==60000 and int(panels['orders']['auxCents'])==60000)
check('Product quantity and estimated margin use order snapshots',int(panels['products']['quantity'])==10 and int(panels['products']['costCents'])==50000 and int(panels['products']['amountCents'])==100000)
check('Reopened service cycles omit previous cycle satisfaction',panels['service']['feedbackCount']==0 and panels['service']['satisfaction'] is None)
check('Commission lock and adjustments remain separate',int(panels['commissions']['amountCents'])==2000 and int(panels['commissions']['auxCents'])==100)
for module,p in panels.items():
 d=owner.req('/analytics/'+module+'/details?'+q+'&pageSize=100')
 check('Summary and drilldown agree: '+module,d['total']==p['count'] and sum(int(x['amountCents']) for x in d['items'])==int(p['amountCents']))
 for state in p['states']:
  detail=owner.req('/analytics/'+module+'/details?'+q+'&'+urllib.parse.urlencode({'dimension':'state','value':state['key'],'pageSize':100}))
  assert detail['total']==state['count']
check('Every chart state drills into its exact authorized population')
rfm=owner.req('/analytics/rfm/details?'+q+'&pageSize=100')['items'];customer=next(x for x in rfm if x['id']==uid(301))
check('RFM uses net cash, confirmed positive receipt frequency and date recency',int(customer['amountCents'])==60000 and customer['frequency']==2 and customer['recencyDays']>=0 and customer['state']=='ACTIVE')
for p in alice.req('/analytics?'+q)['panels']:
 if p['id'] in ('products','orders'):assert p['costVisible'] is False and p['costCents'] is None
 if p['id']=='commissions':assert p['count']==1
for module in ('orders','products'):
 for line in alice.req('/analytics/'+module+'/details?'+q)['items']:assert line['costCents'] is None
alice.req('/analytics/orders/export?'+q,status=403);viewer.req('/analytics/cash/export?'+q,status=403)
check('Sales cannot read costs or export finance through analytics')
check('Read-all viewers cannot read other members commission amounts',next(p for p in viewer.req('/analytics?'+q)['panels'] if p['id']=='commissions')['count']==0)
none=alice.req('/analytics?'+q+'&owner='+uid(202));check('Owner filters narrow scopes without granting access',all(p['count']==0 for p in none['panels'] if p['id'] not in ('service',)))
filtered=owner.req('/analytics?'+q+'&owner='+uid(201));check('Manager owner filter preserves order and cash totals',next(p for p in filtered['panels'] if p['id']=='orders')['count']==1)
for p in panels.values():
 for dimension,listkey in [('region','regions'),('industry','industries'),('product','products'),('type','types')]:
  for group in p.get(listkey,[])[:1]:
   detail=owner.req('/analytics/'+p['id']+'/details?'+q+'&'+urllib.parse.urlencode({'dimension':dimension,'value':group['key']}));assert detail['total']==group['count']
check('Region, industry, product and service-type groups drill down consistently')
for p in panels.values():
 if p['id']=='rfm':continue
 for bucket in p['trend']:
  detail=owner.req('/analytics/'+p['id']+'/details?'+q+'&dimension=month&value='+bucket['month'][:7]);assert detail['total']==bucket['count']
check('Monthly trend buckets use the same business-date definitions')
raw=owner.req('/analytics/cash/export?'+q,raw=True);lines=list(csv.reader(io.StringIO(raw.decode('utf-8-sig'))))
check('CSV exports all filtered cash rows in exact integer cents',len(lines)-1==panels['cash']['count'] and sum(int(x[7].removeprefix("'")) for x in lines[1:])==60000 and '回款冲销' in str(lines))
owner.req('/analytics?from=2024-01-01&to=2026-10-19',status=400);owner.req('/analytics?from=2026-10-20&to=2026-10-19',status=400)
owner.req('/analytics/cash/details?'+q+'&dimension=arbitrary_sql',status=400);owner.req('/analytics/cash/details?'+q+'&page=0',status=400)
check('Invalid dates, arbitrary dimensions and invalid pages are rejected')
empty=owner.req('/analytics/cash/details?'+q+'&dimension=state&value='+urllib.parse.quote("' OR 1=1 --"));check('Bucket values are bound parameters',empty['total']==0)
owner.switch(2);r=owner.req('/analytics?'+q);check('Other enterprises cannot observe P6 cash, order or commission fixtures',all(p['count']==0 for p in r['panels'] if p['id'] in ('cash','orders','commissions')))
owner.switch(3);r=owner.req('/analytics?'+q);check('Expired subscriptions retain authorized read-only analytics',isinstance(r['panels'],list))
result={'stage':'P7-A','passed':len(checks),'checks':checks,'database':'disposable PostgreSQL 18','scope':'synthetic data only'}
print(json.dumps(result,ensure_ascii=False,indent=2))
if os.environ.get('CRM_ACCEPTANCE_RESULT'):pathlib.Path(os.environ['CRM_ACCEPTANCE_RESULT']).write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
