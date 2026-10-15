// P1-B acceptance: only the new disposable native crm_acceptance database.
// Authorized by the user's request to develop P1-B; no original preview records are test targets.
import {SQL} from 'bun';
if(Bun.env.CRM_P1B_WRITE_SCOPE!=='isolated-new-database')throw new Error('P1-B isolated scope required');
const target=new URL(Bun.env.DATABASE_URL!),base=Bun.env.CRM_API_URL!,apiTarget=new URL(base);
if(target.pathname!=='/crm_acceptance'||target.hostname!==Bun.env.CRM_ISOLATED_DATABASE_HOST||apiTarget.hostname!==target.hostname||apiTarget.port!=='8080'||!target.hostname.endsWith('.railway.internal'))throw new Error('Private isolated database and API must match');
const db=new SQL(Bun.env.DATABASE_URL!),checks:string[]=[],clients:C[]=[],fixtureTenantIds:string[]=[];
const suffix=crypto.randomUUID().slice(0,8),initial='P1B-Initial-'+crypto.randomUUID(),password='P1B-Changed-'+crypto.randomUUID();
function check(label:string,ok:boolean){if(!ok)throw new Error(label);checks.push(label);}
class C {
 cookie='';csrf='';tenant='';user:any;
 constructor(){clients.push(this);}
 async req(path:string,method='GET',body?:unknown,headers:Record<string,string>={}){
  const r=await fetch(base+'/api/v1'+path,{method,headers:{Origin:base,'X-CSRF-Token':this.csrf,...(this.cookie?{Cookie:this.cookie}:{}),...(this.tenant?{'X-Workspace-ID':this.tenant}:{}),...(body===undefined?{}:{'Content-Type':'application/json'}),...headers},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(20000)});
  const cookie=r.headers.get('Set-Cookie');if(cookie)this.cookie=cookie.split(';')[0];return{status:r.status,data:r.status===204?null:await r.json()};
 }
 async ok(path:string,method='GET',body?:unknown,headers?:Record<string,string>){const r=await this.req(path,method,body,headers);if(r.status<200||r.status>=300)throw new Error(path+': '+r.status+' '+r.data?.code);return r.data;}
 async login(name:string,pwd:string){const v=await this.ok('/auth/login','POST',{username:name,password:pwd});this.csrf=v.csrfToken;this.user=v.user;this.tenant=v.activeTenantId??'';return v;}
 async select(id:string){const v=await this.ok('/auth/active-tenant','POST',{tenantId:id});this.tenant=id;return v;}
}
const op=new C();let fixturePlanId='';
const once=()=>({'Idempotency-Key':crypto.randomUUID()});
async function initialized(name:string,tenant:string){const c=new C();await c.login(name,initial);await c.ok('/auth/password','POST',{oldPassword:initial,newPassword:password});await c.login(name,password);await c.select(tenant);return c;}
function leadInput(owner:string,name='P1-B 联系人 '+suffix){return{name,companyName:'P1-B 虚构需求 '+name,industry:'制造业',source:'虚构验收',region:'上海',jobTitle:'采购经理',phone:'13800001001',email:'crm-test@example.invalid',description:'P1-B 虚构线索备注',ownerMemberId:owner};}
function editLead(l:any,status:string,overrides:any={}){return{expectedVersion:l.version,name:l.name,companyName:l.companyName,industry:l.industry,source:l.source,region:l.region,jobTitle:l.jobTitle,phone:l.phone,email:l.email,description:l.description,ownerMemberId:l.ownerMemberId,status,...overrides};}
function editContact(c:any,overrides:any={}){return{expectedVersion:c.version,customerId:c.customerId,name:c.name,jobTitle:c.jobTitle,phone:c.phone,email:c.email,primary:c.primary,description:c.description,...overrides};}
async function qualified(c:C,owner:string,name:string){let l=await c.ok('/leads','POST',leadInput(owner,name),once());l=await c.ok('/leads/'+l.id,'PATCH',editLead(l,'CONTACTED'));return c.ok('/leads/'+l.id,'PATCH',editLead(l,'QUALIFIED'));}
async function counts(tenant:string){const rows=await db`SELECT customer_count,(SELECT count(*)::int FROM crm_customer WHERE tenant_id=${tenant}::uuid) AS customers,(SELECT count(*)::int FROM crm_contact WHERE tenant_id=${tenant}::uuid) AS contacts FROM sys_tenant WHERE id=${tenant}::uuid`;return rows[0];}
async function policy(admin:C,role:string,changes:any){const old=(await admin.ok('/workspace/role-policies')).find((p:any)=>p.roleCode===role);return admin.ok('/workspace/role-policies/'+role,'PATCH',{canRead:old.canRead,canWrite:old.canWrite,recordScope:old.recordScope,creditAccess:old.creditAccess,descriptionAccess:old.descriptionAccess,canShare:old.canShare,contactCanRead:old.contactCanRead,contactCanWrite:old.contactCanWrite,leadCanRead:old.leadCanRead,leadCanWrite:old.leadCanWrite,expectedVersion:old.version,...changes});}
try {
 const state=await db`SELECT current_database() AS name,(SELECT count(*)::int FROM flyway_schema_history WHERE success AND version='3') AS v3`;
 check('isolated native target and V3 migration verified',state[0].name==='crm_acceptance'&&state[0].v3===1);
 const anonymous=new C();check('contacts require authenticated session',(await anonymous.req('/contacts')).status===401);check('leads require authenticated session',(await anonymous.req('/leads')).status===401);
 await op.login('platform-operator',Bun.env.CRM_TEST_PASSWORD!);
 check('platform operator has no implicit contact access',(await op.req('/contacts')).status===403);check('platform operator has no implicit lead access',(await op.req('/leads')).status===403);
 let plan=await op.ok('/platform/plans','POST',{code:'p1b-'+suffix,name:'P1-B 虚构验收套餐 '+suffix,monthlyPriceCents:19900,seatLimit:10,customerLimit:2,active:true,expectedVersion:0},once());fixturePlanId=plan.id;
 async function company(code:string){const username=code+'-'+suffix;const v=await op.ok('/platform/tenants','POST',{code:username,name:'P1-B 验收企业 '+code+' '+suffix,industry:'虚构验收',planId:plan.id,trialDays:14,adminUsername:username,adminName:'P1-B 验收管理员',initialPassword:initial},once());fixtureTenantIds.push(v.id);const admin=await initialized(username,v.id);const session=await admin.ok('/auth/session');return {id:v.id,admin,owner:session.tenants.find((t:any)=>t.id===v.id).memberId};}
 const a=await company('p1b-a'),b=await company('p1b-b');
 const firstSession=await a.admin.ok('/auth/session');check('session exposes independent contacts and leads access',firstSession.businessAccess.contactRead&&firstSession.businessAccess.contactWrite&&firstSession.businessAccess.leads.read&&firstSession.businessAccess.leads.write);
 async function member(name:string,roles:string[]){const username='p1b-'+name+'-'+suffix;const m=await a.admin.ok('/workspace/members','POST',{username,name:'P1-B '+name,initialPassword:initial,roles},once());return {id:m.id,user:await initialized(username,a.id)};}
 const sales=await member('sales',['SALES']),otherSales=await member('other-sales',['SALES']),viewer=await member('viewer',['VIEWER']),adminOnly=await member('admin-only',['TENANT_ADMIN']);
 check('administrator-only role does not expose business modules',(await adminOnly.user.req('/contacts')).status===403&&(await adminOnly.user.req('/leads')).status===403);
 check('viewer cannot create contacts',(await viewer.user.req('/contacts','POST',{customerId:crypto.randomUUID(),name:'只读不可新增'},once())).status===403);
 check('viewer cannot create leads',(await viewer.user.req('/leads','POST',leadInput(viewer.id),once())).status===403);
 function customer(owner:string,name:string){return{name,industry:'制造业',level:'B',source:'虚构验收',region:'上海',ownerMemberId:owner,description:'虚构客户备注'};}
 const own=await a.admin.ok('/customers','POST',customer(a.owner,'P1-B 管理客户 '+suffix),once());
 const sale=await sales.user.ok('/customers','POST',customer(sales.id,'P1-B 销售客户 '+suffix),once());
 const foreign=await b.admin.ok('/customers','POST',customer(b.owner,'P1-B 企业 B 客户 '+suffix),once());
 const cbody={customerId:own.id,name:'P1-B 联系人一',jobTitle:'采购经理',phone:'+86 13800001001',email:'contact@example.invalid',primary:true,description:'虚构联系人备注'};
 const contactKey=once();let contact=await a.admin.ok('/contacts','POST',cbody,contactKey);const replay=await a.admin.ok('/contacts','POST',cbody,contactKey);
 check('contact create replay returns the same record',contact.id===replay.id&&(await counts(a.id)).contacts===1);
 check('contact idempotency content conflict rejected',(await a.admin.req('/contacts','POST',{...cbody,name:'其他内容'},contactKey)).status===409);
 check('contact cannot reference another tenant customer',(await a.admin.req('/contacts','POST',{...cbody,customerId:foreign.id},once())).status===404);
 check('sales cannot reference inaccessible customer',(await sales.user.req('/contacts','POST',cbody,once())).status===404);
 check('invalid contact email rejected',(await a.admin.req('/contacts','POST',{...cbody,email:'invalid-address'},once())).status===400);
 check('client tenant injection rejected for contact',(await a.admin.req('/contacts','POST',{...cbody,tenantId:b.id},once())).status===400);
 check('foreign contact detail hidden',(await b.admin.req('/contacts/'+contact.id)).status===404);
 let primary=await a.admin.ok('/contacts','POST',{...cbody,name:'P1-B 联系人二'},once());contact=await a.admin.ok('/contacts/'+contact.id);
 check('new primary contact demotes previous contact and advances its version',!contact.primary&&contact.version===1&&primary.primary);
 check('stale contact edit rejected',(await a.admin.req('/contacts/'+contact.id,'PATCH',editContact(contact,{expectedVersion:0}))).status===409);
 check('contact edit requires an explicit version',(await a.admin.req('/contacts/'+contact.id,'PATCH',{...cbody})).status===400);
 primary=await a.admin.ok('/contacts/'+primary.id,'PATCH',editContact(primary,{jobTitle:'销售总监'}));check('contact edit persists with version increment',primary.jobTitle==='销售总监'&&primary.version===1);
 const options=await sales.user.ok('/customers/options');check('customer selector returns only accessible writable customers',options.items.some((c:any)=>c.id===sale.id)&&!options.items.some((c:any)=>c.id===own.id||c.id===foreign.id));
 check('contact pagination validates bounds',(await a.admin.req('/contacts?pageSize=101')).status===400);
 check('contact search and customer filter are server scoped',(await a.admin.ok('/contacts?customerId='+own.id+'&q='+encodeURIComponent('联系人二'))).items.map((c:any)=>c.id).join(',')===primary.id);
 check('foreign customer contact filter denied',(await a.admin.req('/contacts?customerId='+foreign.id)).status===404);
 await a.admin.ok('/customers/'+own.id+'/shares','POST',{memberId:sales.id,canWrite:false,expiresAt:new Date(Date.now()+3600000).toISOString()});
 let shared=await sales.user.ok('/contacts/'+primary.id);check('read-only customer share exposes contacts read-only',shared.id===primary.id&&!shared.canWrite);
 check('read-only shared contact cannot be edited',(await sales.user.req('/contacts/'+primary.id,'PATCH',editContact(shared))).status===403);
 await a.admin.ok('/customers/'+own.id+'/shares','POST',{memberId:sales.id,canWrite:true,expiresAt:new Date(Date.now()+3600000).toISOString()});
 shared=await sales.user.ok('/contacts/'+primary.id);shared=await sales.user.ok('/contacts/'+primary.id,'PATCH',editContact(shared,{jobTitle:'共享编辑验证'}));check('write customer share allows eligible contact edit',shared.jobTitle==='共享编辑验证');
 await a.admin.ok('/customers/'+own.id+'/shares/'+sales.id,'DELETE');check('share revocation immediately hides related contacts',(await sales.user.req('/contacts/'+primary.id)).status===404);
 const latestOwn=await a.admin.ok('/customers/'+own.id);await a.admin.ok('/customers/'+own.id+'/transfer','POST',{ownerMemberId:sales.id,expectedVersion:latestOwn.version});check('customer transfer updates inherited contact access',(await sales.user.ok('/contacts/'+primary.id)).canWrite===true);
 check('viewer can read contacts but not write',(await viewer.user.ok('/contacts/'+primary.id)).canWrite===false);
 await policy(a.admin,'SALES',{contactCanRead:false,contactCanWrite:false});check('contact module revocation takes effect without relogin',(await sales.user.req('/contacts')).status===403&&(await sales.user.req('/customers')).status===200);
 check('contact module disable does not disable leads',(await sales.user.req('/leads')).status===200);
 await policy(a.admin,'SALES',{contactCanRead:true,contactCanWrite:true,leadCanRead:false,leadCanWrite:false});check('lead module revocation is independent',(await sales.user.req('/leads')).status===403&&(await sales.user.req('/contacts')).status===200);
 await policy(a.admin,'SALES',{leadCanRead:true,leadCanWrite:true});
 const viewerPolicy=(await a.admin.ok('/workspace/role-policies')).find((p:any)=>p.roleCode==='VIEWER');check('viewer module writing rejected',(await a.admin.req('/workspace/role-policies/VIEWER','PATCH',{canRead:true,canWrite:false,recordScope:'ALL',creditAccess:'NONE',descriptionAccess:'READ',canShare:false,contactCanRead:true,contactCanWrite:true,leadCanRead:true,leadCanWrite:false,expectedVersion:viewerPolicy.version})).status===400);
 let lead=await sales.user.ok('/leads','POST',leadInput(sales.id),once());check('new lead starts NEW',lead.status==='NEW'&&lead.version===0&&lead.canWrite);
 const leadKey=once();const otherLead=await a.admin.ok('/leads','POST',leadInput(otherSales.id,'其他成员线索'),leadKey);const otherReplay=await a.admin.ok('/leads','POST',leadInput(otherSales.id,'其他成员线索'),leadKey);check('lead create is idempotent',otherLead.id===otherReplay.id);
 check('sales lead scope hides other owners',(await sales.user.req('/leads/'+otherLead.id)).status===404);
 check('cross-tenant lead detail hidden',(await b.admin.req('/leads/'+lead.id)).status===404);
 check('foreign lead owner rejected',(await a.admin.req('/leads','POST',leadInput(b.owner),once())).status===400);
 check('lead creation cannot inject CONVERTED status',(await sales.user.req('/leads','POST',{...leadInput(sales.id),status:'CONVERTED'},once())).status===400);
 check('lead pagination/status validated',(await a.admin.req('/leads?status=UNKNOWN')).status===400&&(await a.admin.req('/leads?page=0')).status===400);
 check('qualification cannot be skipped',(await sales.user.req('/leads/'+lead.id,'PATCH',editLead(lead,'QUALIFIED'))).status===400);
 check('unqualified lead cannot convert',(await sales.user.req('/leads/'+lead.id+'/convert','POST',{expectedVersion:lead.version,targetCustomerId:sale.id,primaryContact:false},once())).status===400);
 lead=await sales.user.ok('/leads/'+lead.id,'PATCH',editLead(lead,'CONTACTED'));
 check('stale lead version rejected',(await sales.user.req('/leads/'+lead.id,'PATCH',editLead(lead,'QUALIFIED',{expectedVersion:0}))).status===409);
 check('lead update requires explicit version',(await sales.user.req('/leads/'+lead.id,'PATCH',{...leadInput(sales.id),status:'QUALIFIED'})).status===400);
 lead=await sales.user.ok('/leads/'+lead.id,'PATCH',editLead(lead,'QUALIFIED'));check('contact then qualification advances versions',lead.status==='QUALIFIED'&&lead.version===2&&lead.canConvert);
 const fullBefore=await counts(a.id),failedKey=crypto.randomUUID();let response=await sales.user.req('/leads/'+lead.id+'/convert','POST',{expectedVersion:lead.version,customerName:'额度不足客户',customerLevel:'B',ownerMemberId:sales.id,primaryContact:true},{'Idempotency-Key':failedKey});
 check('new-customer conversion obeys customer quota',response.status===409&&response.data.code==='CUSTOMER_QUOTA_EXCEEDED');
 const fullAfter=await counts(a.id);check('quota failure leaves no customer/contact or lead change',fullAfter.customers===fullBefore.customers&&fullAfter.contacts===fullBefore.contacts&&(await sales.user.ok('/leads/'+lead.id)).status==='QUALIFIED');
 const failedRequests=await db`SELECT count(*)::int AS count FROM sys_idempotency_request WHERE tenant_id=${a.id}::uuid AND operation='lead.convert' AND request_key=${failedKey}`;check('failed conversion rolls back its idempotency entry',failedRequests[0].count===0);
 check('conversion cannot reference foreign customer',(await sales.user.req('/leads/'+lead.id+'/convert','POST',{expectedVersion:lead.version,targetCustomerId:foreign.id,primaryContact:false},once())).status===404);
 check('conversion target modes cannot be mixed',(await sales.user.req('/leads/'+lead.id+'/convert','POST',{expectedVersion:lead.version,targetCustomerId:sale.id,customerName:'混合模式',primaryContact:false},once())).status===400);
 const convertKey=once(),convertBody={expectedVersion:lead.version,targetCustomerId:sale.id,primaryContact:true};const converted=await sales.user.ok('/leads/'+lead.id+'/convert','POST',convertBody,convertKey);check('existing-customer conversion works at full customer quota',converted.status==='CONVERTED'&&converted.convertedCustomerId===sale.id&&converted.convertedContactId&&(await counts(a.id)).customers===2);
 const beforeReplay=await counts(a.id),convertedReplay=await sales.user.ok('/leads/'+lead.id+'/convert','POST',convertBody,convertKey);check('conversion replay returns stable customer/contact ids without extra records',convertedReplay.convertedCustomerId===converted.convertedCustomerId&&convertedReplay.convertedContactId===converted.convertedContactId&&(await counts(a.id)).contacts===beforeReplay.contacts);
 check('conversion idempotency content changes rejected',(await sales.user.req('/leads/'+lead.id+'/convert','POST',{...convertBody,primaryContact:false},convertKey)).status===409);
 check('converted lead cannot convert with a second key',(await sales.user.req('/leads/'+lead.id+'/convert','POST',convertBody,once())).status===409);
 check('converted lead remains read-only',(await sales.user.req('/leads/'+lead.id,'PATCH',editLead(converted,'NEW'))).status===409&&!converted.canWrite&&!converted.canConvert);
 const generatedContact=await sales.user.ok('/contacts/'+converted.convertedContactId);check('conversion copies lead contact details',generatedContact.name===lead.name&&generatedContact.phone===lead.phone&&generatedContact.description===lead.description);
 check('converted contact cannot move to another customer',(await sales.user.req('/contacts/'+generatedContact.id,'PATCH',editContact(generatedContact,{customerId:own.id}))).status===409);
 const foreignLead=await qualified(b.admin,b.owner,'企业 B 合格线索');check('foreign lead conversion hidden',(await a.admin.req('/leads/'+foreignLead.id+'/convert','POST',{expectedVersion:foreignLead.version,targetCustomerId:own.id,primaryContact:false},once())).status===404);
 plan=await op.ok('/platform/plans/'+plan.id,'PATCH',{code:plan.code,name:plan.name,monthlyPriceCents:plan.monthlyPriceCents,seatLimit:10,customerLimit:6,active:true,expectedVersion:plan.version});
 await policy(a.admin,'SALES',{descriptionAccess:'NONE'});const newLead=await qualified(sales.user,sales.id,'P1-B 新客户转换');const newConverted=await sales.user.ok('/leads/'+newLead.id+'/convert','POST',{expectedVersion:newLead.version,customerName:'P1-B 转换新增客户 '+suffix,customerLevel:'A',ownerMemberId:sales.id,primaryContact:true},once());
 check('new-customer conversion creates linked customer and contact atomically',newConverted.status==='CONVERTED'&&newConverted.convertedCustomerId&&newConverted.convertedContactId&&(await counts(a.id)).customers===3);
 const newCustomer=await sales.user.ok('/customers/'+newConverted.convertedCustomerId);check('converted customer retains chosen level and owner',newCustomer.level==='A'&&newCustomer.ownerMemberId===sales.id);
 const hiddenNotes=await db`SELECT description FROM crm_customer WHERE id=${newConverted.convertedCustomerId}::uuid`;check('conversion does not bypass protected customer description field',newCustomer.description===null&&hiddenNotes[0].description==='');await policy(a.admin,'SALES',{descriptionAccess:'WRITE'});
 // Inject a database error after customer insertion, only for this newly created fixture.
 const faultName='P1-B 回滚注入-'+suffix,faultLead=await qualified(a.admin,a.owner,faultName),faultBefore=await counts(a.id),faultKey=once();
 await db.unsafe(`CREATE FUNCTION p1b_acceptance_contact_fault() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.tenant_id='${a.id}'::uuid AND NEW.full_name='${faultName}' THEN RAISE EXCEPTION 'isolated acceptance fault' USING ERRCODE='23514'; END IF; RETURN NEW; END; $$`);await db.unsafe('CREATE TRIGGER p1b_acceptance_contact_fault BEFORE INSERT ON crm_contact FOR EACH ROW EXECUTE FUNCTION p1b_acceptance_contact_fault()');
 const faultBody={expectedVersion:faultLead.version,customerName:'P1-B 回滚客户 '+suffix,customerLevel:'B',ownerMemberId:a.owner,primaryContact:true};response=await a.admin.req('/leads/'+faultLead.id+'/convert','POST',faultBody,faultKey);
 const faultAfter=await counts(a.id);check('contact insertion failure rolls back new customer, counters and lead',response.status===409&&faultAfter.customers===faultBefore.customers&&faultAfter.customer_count===faultBefore.customer_count&&faultAfter.contacts===faultBefore.contacts&&(await a.admin.ok('/leads/'+faultLead.id)).version===faultLead.version);
 await db.unsafe('DROP TRIGGER p1b_acceptance_contact_fault ON crm_contact');await db.unsafe('DROP FUNCTION p1b_acceptance_contact_fault()');
 const retried=await a.admin.ok('/leads/'+faultLead.id+'/convert','POST',faultBody,faultKey);check('same failed request key can succeed after rollback',retried.status==='CONVERTED'&&(await counts(a.id)).customers===4);
 const concurrent=await qualified(a.admin,a.owner,'P1-B 同键并发转换'),concurrentKey=once(),concurrentBody={expectedVersion:concurrent.version,customerName:'P1-B 同键客户 '+suffix,customerLevel:'B',ownerMemberId:a.owner,primaryContact:true};
 const same=await Promise.all([a.admin.req('/leads/'+concurrent.id+'/convert','POST',concurrentBody,concurrentKey),a.admin.req('/leads/'+concurrent.id+'/convert','POST',concurrentBody,concurrentKey)]);check('same-key concurrent conversion returns one customer and contact',same.every(r=>r.status===201)&&same[0].data.convertedCustomerId===same[1].data.convertedCustomerId&&same[0].data.convertedContactId===same[1].data.convertedContactId&&(await counts(a.id)).customers===5);
 const distinct=await qualified(a.admin,a.owner,'P1-B 异键并发转换'),distinctBody={expectedVersion:distinct.version,customerName:'P1-B 异键客户 '+suffix,customerLevel:'B',ownerMemberId:a.owner,primaryContact:true};
 const two=await Promise.all([a.admin.req('/leads/'+distinct.id+'/convert','POST',distinctBody,once()),a.admin.req('/leads/'+distinct.id+'/convert','POST',distinctBody,once())]);check('different-key concurrent conversion has one success and one conflict',two.filter(r=>r.status===201).length===1&&two.filter(r=>r.status===409).length===1&&(await counts(a.id)).customers===6);
 plan=await op.ok('/platform/plans/'+plan.id,'PATCH',{code:plan.code,name:plan.name,monthlyPriceCents:plan.monthlyPriceCents,seatLimit:10,customerLimit:7,active:true,expectedVersion:plan.version});
 const last=await qualified(a.admin,a.owner,'P1-B 最后额度竞争');const quotaRace=await Promise.all([a.admin.req('/customers','POST',customer(a.owner,'P1-B 普通新增竞争 '+suffix),once()),a.admin.req('/leads/'+last.id+'/convert','POST',{expectedVersion:last.version,customerName:'P1-B 转换竞争 '+suffix,customerLevel:'B',ownerMemberId:a.owner,primaryContact:true},once())]);
 check('ordinary create and conversion serialize the final customer quota',quotaRace.filter(r=>r.status===201).length===1&&quotaRace.filter(r=>r.status===409&&r.data.code==='CUSTOMER_QUOTA_EXCEEDED').length===1&&(await counts(a.id)).customers===7);
 const afterQuota=await a.admin.ok('/leads/'+last.id);check('losing conversion keeps qualified lead or successful conversion is fully linked',afterQuota.status==='QUALIFIED'||afterQuota.status==='CONVERTED'&&!!afterQuota.convertedContactId);
 const d1=await a.admin.ok('/workspace/departments','POST',{name:'P1-B 部门一',active:true,expectedVersion:0},once()),d2=await a.admin.ok('/workspace/departments','POST',{name:'P1-B 部门二',active:true,expectedVersion:0},once());
 async function setMember(id:string,department:string,roles:string[]){const old=(await a.admin.ok('/workspace/members')).find((m:any)=>m.id===id);await a.admin.ok('/workspace/members/'+id,'PATCH',{name:old.displayName,departmentId:department,roles,status:'ACTIVE',expectedVersion:old.version});}
 await setMember(sales.id,d1.id,['SALES']);await setMember(otherSales.id,d2.id,['SALES']);await policy(a.admin,'SALES',{recordScope:'DEPARTMENT'});
 check('department lead scope excludes another department',(await sales.user.req('/leads/'+otherLead.id)).status===404&&(await sales.user.ok('/leads')).items.every((l:any)=>l.ownerMemberId===sales.id));
 await setMember(sales.id,d1.id,['SALES','VIEWER']);let wide=await sales.user.ok('/leads/'+otherLead.id);check('combined roles broaden reading without broadening lead writing',wide.id===otherLead.id&&!wide.canWrite&&(await sales.user.req('/leads/'+otherLead.id,'PATCH',editLead(wide,'CONTACTED'))).status===403);
 await setMember(sales.id,d1.id,['SALES']);check('role withdrawal immediately hides out-of-scope lead',(await sales.user.req('/leads/'+otherLead.id)).status===404);await policy(a.admin,'SALES',{recordScope:'OWN_SHARED'});
 await a.admin.ok('/customers/'+newConverted.convertedCustomerId+'/transfer','POST',{ownerMemberId:otherSales.id,expectedVersion:newCustomer.version});const masked=await sales.user.ok('/leads/'+newLead.id);check('conversion result masks inaccessible customer and contact references',masked.status==='CONVERTED'&&masked.convertedCustomerId===null&&masked.convertedContactId===null&&masked.convertedCustomerName===null);
 const grant=await a.admin.ok('/workspace/support/grants','POST',{operatorUserId:op.user.id,reason:'P1-B 虚构客户支持边界',hours:1,allowCredit:false,allowDescription:false},once());await op.ok('/platform/support/'+grant.id+'/enter','POST');op.tenant=a.id;
 check('customer support grant cannot read contacts or leads',(await op.req('/contacts')).status===403&&(await op.req('/leads')).status===403);await op.ok('/platform/support/exit','POST');op.tenant='';
 const role=await db`SELECT rolname,rolsuper,rolbypassrls FROM pg_roles WHERE rolname='crm_runtime'`;check('runtime role is neither superuser nor RLS bypass',!role[0].rolsuper&&!role[0].rolbypassrls);
 const protectedTables=await db`SELECT relname,relrowsecurity,relforcerowsecurity,pg_get_userbyid(relowner) AS owner FROM pg_class WHERE relname IN ('crm_customer','crm_customer_share','sys_idempotency_request','sys_audit_log','org_department','sys_role_policy','sys_support_access_log','crm_contact','crm_lead')`;check('all nine scoped tables force RLS with separate ownership',protectedTables.length===9&&protectedTables.every((t:any)=>t.relrowsecurity&&t.relforcerowsecurity&&t.owner==='crm_migrator'));
 const runtimeUrl=new URL(Bun.env.DATABASE_URL!);runtimeUrl.username='crm_runtime';runtimeUrl.password=Bun.env.CRM_RUNTIME_PASSWORD!;const limited=new SQL(runtimeUrl.toString());const closed=await limited`SELECT (SELECT count(*) FROM crm_contact)::int AS contacts,(SELECT count(*) FROM crm_lead)::int AS leads`;check('contacts and leads fail closed without tenant context',closed[0].contacts===0&&closed[0].leads===0);
 await limited.begin(async sql=>{await sql`SELECT set_config('crm.tenant_id',${a.id},true)`;await sql.unsafe(`DO $$ BEGIN BEGIN INSERT INTO crm_contact(id,tenant_id,customer_id,full_name) VALUES('${crypto.randomUUID()}','${a.id}','${foreign.id}','P1-B FK 拒绝'); RAISE EXCEPTION 'foreign-key guard not enforced'; EXCEPTION WHEN foreign_key_violation THEN NULL; END; END; $$`);});check('composite FK rejects cross-tenant contact association',true);
 await limited.begin(async sql=>{await sql`SELECT set_config('crm.tenant_id',${a.id},true)`;await sql.unsafe(`DO $$ BEGIN BEGIN INSERT INTO crm_contact(id,tenant_id,customer_id,full_name) VALUES('${crypto.randomUUID()}','${b.id}','${foreign.id}','P1-B RLS 拒绝'); RAISE EXCEPTION 'RLS guard not enforced'; EXCEPTION WHEN insufficient_privilege THEN NULL; END; END; $$`);});check('runtime RLS rejects foreign-tenant contact writes',true);await limited.close();
 const tls=await db`SELECT count(*)::int AS total,count(*) FILTER(WHERE ssl)::int AS encrypted FROM pg_stat_ssl WHERE pid IN (SELECT pid FROM pg_stat_activity WHERE usename='crm_runtime' AND application_name='PostgreSQL JDBC Driver')`;check('Java runtime database connections use TLS',tls[0].total>0&&tls[0].total===tls[0].encrypted);
 const consistent=await counts(a.id);check('conversion quota counter equals actual customer count',consistent.customer_count===consistent.customers&&consistent.customers===7);
 const originals=await db`SELECT count(*)::int AS count FROM crm_lead WHERE tenant_id=${a.id}::uuid AND status='CONVERTED' AND (converted_customer_id IS NULL OR converted_contact_id IS NULL OR converted_at IS NULL)`;check('every converted lead has complete linked conversion result',originals[0].count===0);
 await db`UPDATE sys_tenant SET subscription_end=now()-interval '1 minute' WHERE id=${a.id}::uuid`;check('expired tenant can read contacts and leads',(await a.admin.req('/contacts')).status===200&&(await a.admin.req('/leads')).status===200);
 check('expired tenant cannot write contacts or leads',(await a.admin.req('/contacts','POST',cbody,once())).status===403&&(await a.admin.req('/leads','POST',leadInput(a.owner),once())).status===403);check('expired tenant cannot convert',(await a.admin.req('/leads/'+last.id+'/convert','POST',{expectedVersion:afterQuota.version,targetCustomerId:own.id,primaryContact:false},once())).status===403);
 const businessAudit=await db`SELECT action,count(*)::int AS count FROM sys_audit_log WHERE tenant_id=${a.id}::uuid AND entity_type IN ('CONTACT','LEAD') GROUP BY action ORDER BY action`;check('contact and lead changes have metadata audit records',businessAudit.some((r:any)=>r.action==='CONTACT_CREATED')&&businessAudit.some((r:any)=>r.action==='LEAD_CONVERTED'));
 const primaryCounts=await db`SELECT customer_id,count(*)::int AS count FROM crm_contact WHERE tenant_id=${a.id}::uuid AND is_primary GROUP BY customer_id`;check('each customer has at most one primary contact',primaryCounts.every((r:any)=>r.count===1));
 for(const id of fixtureTenantIds){const current=(await op.ok('/platform/tenants')).find((t:any)=>t.id===id);await op.ok('/platform/tenants/'+id,'PATCH',{planId:current.planId,status:'SUSPENDED',expectedVersion:current.version});}
 check('both P1-B fixture companies suspended after acceptance',(await a.admin.req('/contacts')).status===403&&(await b.admin.req('/leads')).status===403);
 console.log(JSON.stringify({p1bPassed:checks.length,checks,fixtureTenantIds,fixturePlanId,scope:'isolated-new-database',businessAudit,connections:tls[0],counters:consistent}));
} catch(error){console.error(JSON.stringify({p1bFailed:true,error:String(error),completed:checks,fixtureTenantIds}));throw error;}
finally {
 for(const id of fixtureTenantIds){try{const current=(await op.ok('/platform/tenants')).find((t:any)=>t.id===id);if(current.status!=='SUSPENDED')await op.ok('/platform/tenants/'+id,'PATCH',{planId:current.planId,status:'SUSPENDED',expectedVersion:current.version});}catch{}}
 await Promise.all(clients.filter(c=>c.cookie).map(c=>c.req('/auth/logout','POST').catch(()=>undefined)));await db.close();
}
