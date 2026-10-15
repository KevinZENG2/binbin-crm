// P0 write regression for a disposable native database only; never target the original HTTPS preview.
const boundedApi=new URL(Bun.env.CRM_API_URL!),boundedDatabase=new URL(Bun.env.DATABASE_URL!);
if(boundedApi.protocol!=='http:'||!boundedApi.hostname.endsWith('.railway.internal')||boundedApi.hostname!==boundedDatabase.hostname||boundedApi.hostname!==Bun.env.CRM_ISOLATED_DATABASE_HOST||boundedApi.port!=='8080'||boundedDatabase.pathname!=='/crm_acceptance'||Bun.env.CRM_P1A_WRITE_SCOPE!=='isolated-new-database')throw new Error('P0 writes require the private disposable native target');
const base=Bun.env.CRM_API_URL!,password=Bun.env.CRM_TEST_PASSWORD!;
const apiChecks:string[]=[];
const id=(n:number)=>`00000000-0000-4000-8000-${String(n).padStart(12,'0')}`;
function pass(label:string,value:boolean){if(!value)throw new Error(label);apiChecks.push(label);}
class Client {
 csrf='';cookie='';
 async req(path:string,method='GET',body?:unknown,headers:Record<string,string>={}){
  const r=await fetch(base+'/api/v1'+path,{method,headers:{Origin:base,'X-CSRF-Token':this.csrf,...(this.cookie?{Cookie:this.cookie}:{}),...(body===undefined?{}:{'Content-Type':'application/json'}),...headers},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(15000)});
  const set=r.headers.get('Set-Cookie');if(set)this.cookie=set.split(';')[0];const data=r.status===204?null:await r.json();return {status:r.status,data,headers:r.headers};
 }
 async login(name:string){const r=await this.req('/auth/login','POST',{username:name,password});pass('login '+name,r.status===200);this.csrf=r.data.csrfToken;return r;}
 select(n:number){return this.req('/auth/active-tenant','POST',{tenantId:id(n)});}
}
const input=(name:string,owner:number)=>({name,industry:'验收行业',level:'B',source:'自动验收',region:'上海',creditCode:'',ownerMemberId:id(owner),description:'虚构验收资料'});
const anon=new Client();let r=await anon.req('/customers');pass('anonymous access denied',r.status===401);
r=await anon.req('/health');pass('Java/PostgreSQL readiness',r.status===200&&r.data.database==='PostgreSQL');
r=await anon.req('/auth/login','POST',{username:'unknown-cloud-account',password});pass('unknown user generic rejection',r.status===401&&r.data.code==='LOGIN_FAILED');
const owner=new Client();r=await owner.login('owner');const cookie=r.headers.get('Set-Cookie')!;
pass('secure HttpOnly SameSite session', ['Secure','HttpOnly','SameSite=Lax','Path=/api/v1'].every(t=>cookie.includes(t)));
pass('self tenant discovery',r.data.tenants.length===3&&r.data.tenants.every((t:any)=>[id(1),id(2),id(3)].includes(t.id)));
r=await owner.req('/customers');pass('no active tenant denied',r.status===403);
r=await owner.req('/auth/active-tenant','POST',{tenantId:id(1)},{'X-CSRF-Token':''});pass('missing CSRF denied',r.status===403);
r=await owner.select(1);pass('authorized tenant switch',r.status===200);
r=await owner.req('/customers');pass('tenant A list scoped',r.status===200&&r.data.total>=3&&!r.data.items.some((c:any)=>c.id===id(304)));
r=await owner.req('/customers/'+id(304));pass('tenant B detail hidden',r.status===404);
const key='cloud-check-'+crypto.randomUUID(),body=input('云端验收客户 '+crypto.randomUUID().slice(0,8),201);
r=await owner.req('/customers','POST',body,{'Idempotency-Key':key});pass('customer persisted',r.status===201);const created=r.data.id;
r=await owner.req('/customers','POST',body,{'Idempotency-Key':key});pass('idempotent create replay',r.status===201&&r.data.id===created);
r=await owner.req('/customers','POST',{...body,name:'修改请求内容'},{'Idempotency-Key':key});pass('idempotency content conflict',r.status===409);
r=await owner.req('/customers','POST',{...body,tenantId:id(2)},{'Idempotency-Key':crypto.randomUUID()});pass('client tenant injection rejected',r.status===400);
r=await owner.req('/customers','POST',{...body,ownerMemberId:id(210)},{'Idempotency-Key':crypto.randomUUID()});pass('foreign tenant owner rejected',r.status===400);
const edit={expectedVersion:0,name:body.name+' 已更新',industry:body.industry,level:body.level,source:body.source,region:body.region,description:body.description};
r=await owner.req('/customers/'+created,'PATCH',edit);pass('versioned customer update',r.status===200&&r.data.version===1);
r=await owner.req('/customers/'+created,'PATCH',edit);pass('stale update conflicts',r.status===409);
r=await owner.req('/customers/'+created,'PATCH',{...edit,expectedVersion:1,ownerMemberId:id(210)});pass('owner reassignment injection rejected',r.status===400);
r=await owner.req('/customers?pageSize=101');pass('page size bounded',r.status===400);
r=await owner.req('/customers?pageSize=1');pass('database pagination',r.status===200&&r.data.items.length===1&&r.data.total>=4);
r=await owner.req('/customers','POST',body,{'Idempotency-Key':crypto.randomUUID(),Origin:'https://untrusted.example'});pass('untrusted origin denied',r.status===403);
r=await owner.select(2);pass('tenant B switch allowed for member',r.status===200);
r=await owner.req('/customers');pass('tenant B list isolated',r.status===200&&r.data.total===1&&r.data.items[0].id===id(304));
r=await owner.req('/customers/'+created);pass('tenant A new record hidden after switch',r.status===404);
const alice=new Client();await alice.login('alice');r=await alice.select(2);pass('nonmember tenant switch denied',r.status===404);await alice.select(1);
r=await alice.req('/customers');const ids=r.data.items.map((c:any)=>c.id);pass('sales sees own and shared customers',r.status===200&&ids.includes(id(301))&&ids.includes(id(303))&&!ids.includes(id(302))&&ids.includes(created));
r=await alice.req('/customers/'+id(302));pass('other sales detail hidden',r.status===404);
r=await alice.req('/customers/'+id(303),'PATCH',edit);pass('read share cannot edit',r.status===403);
r=await alice.req('/customers','POST',input('越权分配示例',202),{'Idempotency-Key':crypto.randomUUID()});pass('sales cannot assign customer to others',r.status===400);
const viewer=new Client();await viewer.login('viewer');await viewer.select(1);r=await viewer.req('/customers');pass('viewer can read',r.status===200);
r=await viewer.req('/customers','POST',input('只读新增示例',201),{'Idempotency-Key':crypto.randomUUID()});pass('viewer cannot create',r.status===403);
const tech=new Client();await tech.login('tech-admin');await tech.select(1);r=await tech.req('/customers');pass('technical admin has no implicit customer access',r.status===403);
const operator=new Client();r=await operator.login('platform-operator');pass('operator has no implicit tenant membership',r.data.tenants.length===0);r=await operator.select(1);pass('operator cannot enter tenant',r.status===404);
await owner.select(3);r=await owner.req('/customers');pass('expired subscription can read',r.status===200&&r.data.total===1);
r=await owner.req('/customers','POST',input('到期写入示例',220),{'Idempotency-Key':crypto.randomUUID()});pass('expired subscription cannot write',r.status===403);
r=await owner.req('/auth/logout','POST');pass('logout revokes session',r.status===204);r=await owner.req('/auth/session');pass('logged out session rejected',r.status===401);
await owner.login('owner');await owner.select(1);r=await owner.req('/customers/'+created);pass('data survives a new login',r.status===200&&r.data.name===edit.name);
await Promise.all([owner,alice,viewer,tech,operator].map(c=>c.req('/auth/logout','POST')));
console.log(JSON.stringify({apiPassed:apiChecks.length,checks:apiChecks,acceptanceRecordId:created}));
