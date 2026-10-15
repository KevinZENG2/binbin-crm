// Published preview verification: normal authentication and GETs only; no business writes.
const base=Bun.env.CRM_API_URL!,password=Bun.env.CRM_TEST_PASSWORD!,checks:string[]=[];
if(!base.startsWith('https://'))throw new Error('HTTPS preview required');
function check(name:string,ok:boolean){if(!ok)throw new Error(name);checks.push(name);}
class Reader {
 cookie='';csrf='';tenant='';
 async req(path:string,method='GET',body?:unknown){const r=await fetch(base+'/api/v1'+path,{method,headers:{Origin:base,...(this.cookie?{Cookie:this.cookie}:{}),'X-CSRF-Token':this.csrf,...(this.tenant?{'X-Workspace-ID':this.tenant}:{}),...(body===undefined?{}:{'Content-Type':'application/json'})},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(20000)});const c=r.headers.get('Set-Cookie');if(c)this.cookie=c.split(';')[0];return{status:r.status,data:r.status===204?null:await r.json()};}
 async login(name:string){const r=await this.req('/auth/login','POST',{username:name,password});check('normal login '+name,r.status===200);this.csrf=r.data.csrfToken;return r.data;}
}
const owner=new Reader(),op=new Reader(),anonymous=new Reader();
try {
 const health=await anonymous.req('/health');check('published Java v0.4 ready over HTTPS',health.status===200&&health.data.version==='0.4.0'&&health.data.status==='UP');
 check('anonymous contacts rejected',(await anonymous.req('/contacts')).status===401);check('anonymous leads rejected',(await anonymous.req('/leads')).status===401);
 const session=await owner.login('owner');const tenant=session.tenants.find((t:any)=>t.code==='test-1'&&t.status==='ACTIVE')??session.tenants.find((t:any)=>t.status==='ACTIVE');if(!tenant)throw new Error('No active self-owned preview workspace');
 const selection=await owner.req('/auth/active-tenant','POST',{tenantId:tenant.id});check('select own workspace',selection.status===200);owner.tenant=tenant.id;
 const view=await owner.req('/auth/session');check('session includes module access',view.status===200&&typeof view.data.businessAccess.contactRead==='boolean'&&typeof view.data.businessAccess.leads.read==='boolean');
 const access=view.data.businessAccess,customer=view.data.customerAccess;
 for(const path of ['/contacts','/leads','/leads/owners','/customers/options']){const read=path.startsWith('/contacts')?access.contactRead:path.startsWith('/leads')?access.leads.read:!!customer?.read;const r=await owner.req(path);check('GET '+path+' follows current permission',r.status===(read?200:403));if(r.status===200&&path!=='/leads/owners')check(path+' is a server page',Array.isArray(r.data.items)&&Number.isInteger(r.data.total));}
 if(access.contactRead){check('contact detail missing is hidden',(await owner.req('/contacts/'+crypto.randomUUID())).status===404);check('contact pagination validated',(await owner.req('/contacts?pageSize=101')).status===400);}
 if(access.leads.read){check('lead detail missing is hidden',(await owner.req('/leads/'+crypto.randomUUID())).status===404);check('lead status filter validated',(await owner.req('/leads?status=UNKNOWN')).status===400);}
 if(tenant.roles.includes('TENANT_ADMIN')){const p=await owner.req('/workspace/role-policies');check('four policies remain available',p.status===200&&p.data.length===4);check('policies expose all module switches',p.data.every((r:any)=>['contactCanRead','contactCanWrite','leadCanRead','leadCanWrite'].every(k=>typeof r[k]==='boolean')));}
 await op.login('platform-operator');check('operator contacts denied without grant',(await op.req('/contacts')).status===403);check('operator leads denied without grant',(await op.req('/leads')).status===403);
 const tenancy=await owner.req('/contacts','GET');check('workspace header mismatch rejected',(await fetch(base+'/api/v1/contacts',{headers:{Cookie:owner.cookie,'X-Workspace-ID':crypto.randomUUID()},signal:AbortSignal.timeout(15000)})).status===409);
 console.log(JSON.stringify({readonlyPassed:checks.length,checks,businessWrites:false,version:health.data.version,scope:'existing preview: normal login, self workspace selection, GET and logout only'}));
}finally {await Promise.all([owner,op].filter(c=>c.cookie).map(c=>c.req('/auth/logout','POST').catch(()=>undefined)));}
