// PERSISTENT WRITE acceptance draft. Execute only after approval, in the test environment, on newly created P1-A fixtures.
import { SQL } from 'bun';
const writeScope=Bun.env.CRM_P1A_WRITE_SCOPE;
if(!['new-fixtures-only','isolated-new-database'].includes(writeScope??''))throw new Error('P1-A write acceptance requires a bounded fixture scope.');
const p1base=Bun.env.CRM_API_URL!,seedPassword=Bun.env.CRM_TEST_PASSWORD!,db=new SQL(Bun.env.DATABASE_URL!);
const p1checks:string[]=[],suffix=crypto.randomUUID().slice(0,8),initial='Initial-'+crypto.randomUUID(),changed='Changed-'+crypto.randomUUID();
function p1check(label:string,value:boolean){if(!value)throw new Error(label);p1checks.push(label);}
class P1Client{
 csrf='';cookie='';tenant='';user:any;
 async request(path:string,method='GET',body?:unknown,headers:Record<string,string>={}){const r=await fetch(p1base+'/api/v1'+path,{method,headers:{Origin:p1base,'X-CSRF-Token':this.csrf,...(this.cookie?{Cookie:this.cookie}:{}),...(this.tenant?{'X-Workspace-ID':this.tenant}:{}),...(body===undefined?{}:{'Content-Type':'application/json'}),...headers},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(20000)});const cookie=r.headers.get('Set-Cookie');if(cookie)this.cookie=cookie.split(';')[0];const data=r.status===204?null:await r.json();return {status:r.status,data};}
 async ok(path:string,method='GET',body?:unknown,headers?:Record<string,string>){const r=await this.request(path,method,body,headers);if(r.status<200||r.status>=300)throw new Error(path+': '+r.status+' '+r.data?.code);return r.data;}
 async login(username:string,password:string){const value=await this.ok('/auth/login','POST',{username,password});this.csrf=value.csrfToken;this.tenant=value.activeTenantId??'';this.user=value.user;return value;}
 async select(id:string){const value=await this.ok('/auth/active-tenant','POST',{tenantId:id});this.tenant=id;return value;}
 async password(oldPassword:string,newPassword:string){await this.ok('/auth/password','POST',{oldPassword,newPassword});}
}
if(writeScope==='isolated-new-database'){
 const isolation=await db`SELECT current_database() AS name,(SELECT count(*)::int FROM sys_tenant) AS tenants,(SELECT count(*)::int FROM iam_user) AS users`;
 if(isolation[0].name!=='crm_acceptance'||isolation[0].tenants!==3||isolation[0].users!==6)throw new Error('Refusing write tests: database is not the fresh isolated acceptance database.');
 const target=new URL(Bun.env.DATABASE_URL!);if(target.hostname!==Bun.env.CRM_ISOLATED_DATABASE_HOST||!p1base.endsWith('.railway.internal:8080'))throw new Error('Refusing write tests: isolated private targets do not match.');
 p1check('fresh independent native database verified before any business writes',true);
}
try {
const op=new P1Client();await op.login('platform-operator',seedPassword);
let p1r=await op.request('/customers');p1check('platform has no automatic customer access',p1r.status===403);
const planBody={code:'p1a-'+suffix,name:'P1-A 自动验收套餐 '+suffix,monthlyPriceCents:12345,seatLimit:2,customerLimit:2,active:true,expectedVersion:0};
const planKey=crypto.randomUUID(),plan=await op.ok('/platform/plans','POST',planBody,{'Idempotency-Key':planKey});
p1check('create real plan with integer cents',plan.monthlyPriceCents===12345);
const planReplay=await op.ok('/platform/plans','POST',planBody,{'Idempotency-Key':planKey});p1check('plan creation idempotent',planReplay.id===plan.id);
const tenantBody={code:'p1a-a-'+suffix,name:'P1-A 验收企业 A '+suffix,industry:'虚构验收',planId:plan.id,trialDays:14,adminUsername:'p1owner-'+suffix,adminName:'验收管理员',initialPassword:initial};
const tenantKey=crypto.randomUUID(),company=await op.ok('/platform/tenants','POST',tenantBody,{'Idempotency-Key':tenantKey});
p1check('tenant initial member uses one seat',company.activeMemberCount===1);
const replay=await op.ok('/platform/tenants','POST',tenantBody,{'Idempotency-Key':tenantKey});p1check('tenant creation idempotent',replay.id===company.id);
const admin=new P1Client();const firstLogin=await admin.login(tenantBody.adminUsername,initial);p1check('new account requires first password change',firstLogin.user.mustChangePassword===true);
await admin.select(company.id);p1r=await admin.request('/workspace/members');p1check('initial password cannot manage organization',p1r.status===403&&p1r.data.code==='PASSWORD_CHANGE_REQUIRED');
await admin.password(initial,changed);p1r=await admin.request('/auth/session');p1check('password change invalidates old session',p1r.status===401);await admin.login(tenantBody.adminUsername,changed);await admin.select(company.id);
const team=await admin.ok('/workspace/members'),manager=team[0];p1check('initial admin has management and sales roles',manager.roles.includes('TENANT_ADMIN')&&manager.roles.includes('SALES_MANAGER'));
const d1=await admin.ok('/workspace/departments','POST',{name:'销售一部',parentId:null,active:true,expectedVersion:0},{'Idempotency-Key':crypto.randomUUID()});
const d2=await admin.ok('/workspace/departments','POST',{name:'销售二组',parentId:d1.id,active:true,expectedVersion:0},{'Idempotency-Key':crypto.randomUUID()});p1check('departments persisted with parent',d2.parentId===d1.id);
p1r=await admin.request('/workspace/departments/'+d1.id,'PATCH',{name:d1.name,parentId:d2.id,active:true,expectedVersion:0});p1check('department cycle denied',p1r.status===400);
const memberBodies=[0,1].map(n=>({username:'p1sales-'+n+'-'+suffix,name:'验收销售 '+n,initialPassword:initial,departmentId:d2.id,roles:['SALES','VIEWER']}));
const seatResults=await Promise.all(memberBodies.map(body=>admin.request('/workspace/members','POST',body,{'Idempotency-Key':crypto.randomUUID()})));
p1check('concurrent seat allocation permits only one remaining seat',seatResults.filter(r=>r.status===200).length===1&&seatResults.filter(r=>r.status===409&&r.data.code==='SEAT_QUOTA_EXCEEDED').length===1);
let salesMember=seatResults.find(r=>r.status===200)!.data;const sales=new P1Client();await sales.login(salesMember.username,initial);await sales.password(initial,changed);await sales.login(salesMember.username,changed);await sales.select(company.id);
p1r=await sales.request('/workspace/members');p1check('sales cannot manage members',p1r.status===403);p1r=await sales.request('/platform/tenants');p1check('tenant member cannot operate platform',p1r.status===403);
p1r=await admin.request('/workspace/members/'+manager.id,'PATCH',{name:manager.displayName,departmentId:d1.id,roles:['SALES_MANAGER'],status:'ACTIVE',expectedVersion:manager.version});p1check('last active tenant admin protected',p1r.status===409);
const customerBody=(name:string,owner:string,code='')=>({name,industry:'验收',level:'B',source:'自动验收',region:'上海',creditCode:code,ownerMemberId:owner,description:'验收敏感备注'});
const c1key=crypto.randomUUID(),c1body=customerBody('管理员客户',manager.id,'123456789012345678');
let c1=await admin.ok('/customers','POST',c1body,{'Idempotency-Key':c1key});
const slots=await Promise.all([0,1].map(n=>admin.request('/customers','POST',customerBody('销售客户 '+n,salesMember.id),{'Idempotency-Key':crypto.randomUUID()})));
p1check('concurrent customer allocation permits only one remaining slot',slots.filter(r=>r.status===201).length===1&&slots.filter(r=>r.status===409&&r.data.code==='CUSTOMER_QUOTA_EXCEEDED').length===1);
let c2=slots.find(r=>r.status===201)!.data;
const repeated=await admin.ok('/customers','POST',c1body,{'Idempotency-Key':c1key});p1check('replay still succeeds at customer quota',repeated.id===c1.id);
const all=await sales.ok('/customers');p1check('mixed viewer sales roles read all but only write owned records',all.total===2&&all.items.find((c:any)=>c.id===c1.id).canWrite===false&&all.items.find((c:any)=>c.id===c2.id).canWrite===true);
salesMember=await admin.ok('/workspace/members/'+salesMember.id,'PATCH',{name:salesMember.displayName,departmentId:d2.id,roles:['SALES'],status:'ACTIVE',expectedVersion:salesMember.version});
p1r=await sales.request('/customers/'+c1.id);p1check('role removal immediately shrinks record visibility',p1r.status===404);
let policies=await admin.ok('/workspace/role-policies');const sp=policies.find((p:any)=>p.roleCode==='SALES');
let policy=await admin.ok('/workspace/role-policies/SALES','PATCH',{canRead:true,canWrite:true,recordScope:'OWN_SHARED',creditAccess:'NONE',descriptionAccess:'NONE',canShare:true,expectedVersion:sp.version});
let masked=await sales.ok('/customers/'+c2.id);p1check('field masking enforced in API',masked.creditCode===null&&masked.description===null&&!masked.creditWritable&&!masked.descriptionWritable);
p1r=await sales.request('/customers/'+c2.id,'PATCH',{expectedVersion:c2.version,name:c2.name,industry:c2.industry,level:c2.level,source:c2.source,region:c2.region,description:'越权修改'});p1check('hidden field write rejected',p1r.status===403);
c2=await sales.ok('/customers/'+c2.id,'PATCH',{expectedVersion:c2.version,name:c2.name+' 更新',industry:c2.industry,level:c2.level,source:c2.source,region:c2.region});p1check('permitted core edit preserves hidden fields',c2.description===null&&c2.version===1);
const stored=await db`SELECT description FROM crm_customer WHERE id=${c2.id}::uuid`;p1check('hidden description remains intact in database',stored[0].description==='验收敏感备注');
policy=await admin.ok('/workspace/role-policies/SALES','PATCH',{canRead:true,canWrite:true,recordScope:'DEPARTMENT',creditAccess:'READ',descriptionAccess:'READ',canShare:true,expectedVersion:policy.version});
p1r=await sales.request('/customers/'+c1.id);p1check('department record scope excludes unassigned manager records',p1r.status===404);
policy=await admin.ok('/workspace/role-policies/SALES','PATCH',{canRead:true,canWrite:true,recordScope:'OWN_SHARED',creditAccess:'READ',descriptionAccess:'READ',canShare:true,expectedVersion:policy.version});
await admin.ok('/customers/'+c1.id+'/shares','POST',{memberId:salesMember.id,canWrite:false,expiresAt:new Date(Date.now()+3600000).toISOString()});masked=await sales.ok('/customers/'+c1.id);p1check('read share grants only read',masked.canWrite===false);
await admin.ok('/customers/'+c1.id+'/shares','POST',{memberId:salesMember.id,canWrite:true,expiresAt:new Date(Date.now()+3600000).toISOString()});masked=await sales.ok('/customers/'+c1.id);p1check('write share does not permit re-delegation or transfer',masked.canWrite===true&&!masked.canShare&&!masked.canTransfer);
await admin.ok('/customers/'+c1.id+'/shares/'+salesMember.id,'DELETE');p1r=await sales.request('/customers/'+c1.id);p1check('share revocation effective immediately',p1r.status===404);
c1=await admin.ok('/customers/'+c1.id+'/transfer','POST',{ownerMemberId:salesMember.id,expectedVersion:c1.version});p1check('transfer uses version and changes owner',c1.ownerMemberId===salesMember.id&&c1.version===1);
p1r=await admin.request('/workspace/departments/'+d2.id,'PATCH',{name:d2.name,parentId:d1.id,active:false,expectedVersion:d2.version});p1check('occupied department cannot be disabled',p1r.status===409);
salesMember=await admin.ok('/workspace/members/'+salesMember.id,'PATCH',{name:salesMember.displayName,departmentId:d2.id,roles:['SALES'],status:'DISABLED',expectedVersion:salesMember.version});
p1r=await sales.request('/customers');p1check('disabled membership immediately denies business access',p1r.status===404);
const viewerMember=await admin.ok('/workspace/members','POST',{username:'p1viewer-'+suffix,name:'验收只读成员',initialPassword:initial,departmentId:d2.id,roles:['VIEWER']},{'Idempotency-Key':crypto.randomUUID()});
p1r=await admin.request('/workspace/members/'+salesMember.id,'PATCH',{name:salesMember.displayName,departmentId:d2.id,roles:['SALES'],status:'ACTIVE',expectedVersion:salesMember.version});p1check('re-enable member must respect seat quota',p1r.status===409&&p1r.data.code==='SEAT_QUOTA_EXCEEDED');
p1r=await op.request('/platform/plans/'+plan.id,'PATCH',{...planBody,seatLimit:1,expectedVersion:plan.version});p1check('plan cannot shrink below existing usage',p1r.status===409);
const raised=await op.ok('/platform/plans/'+plan.id,'PATCH',{...planBody,seatLimit:3,customerLimit:3,expectedVersion:plan.version});
salesMember=await admin.ok('/workspace/members/'+salesMember.id,'PATCH',{name:salesMember.displayName,departmentId:d2.id,roles:['SALES'],status:'ACTIVE',expectedVersion:salesMember.version});p1check('upgrade releases capacity for reactivation',salesMember.status==='ACTIVE');
const viewer=new P1Client();await viewer.login(viewerMember.username,initial);await viewer.password(initial,changed);await viewer.login(viewerMember.username,changed);await viewer.select(company.id);
masked=await viewer.ok('/customers/'+c1.id);p1check('viewer credit field hidden and writes denied',masked.creditCode===null&&masked.creditReadable===false&&masked.canWrite===false);
const other=await op.ok('/platform/tenants','POST',{...tenantBody,code:'p1a-b-'+suffix,name:'P1-A 验收企业 B '+suffix,adminUsername:'p1other-'+suffix,planId:'00000000-0000-4000-8000-000000000501'},{'Idempotency-Key':crypto.randomUUID()});
const otherAdmin=new P1Client();await otherAdmin.login('p1other-'+suffix,initial);await otherAdmin.password(initial,changed);await otherAdmin.login('p1other-'+suffix,changed);await otherAdmin.select(other.id);const otherDept=await otherAdmin.ok('/workspace/departments','POST',{name:'其他企业部门',parentId:null,active:true,expectedVersion:0},{'Idempotency-Key':crypto.randomUUID()});
p1r=await admin.request('/workspace/departments/'+otherDept.id,'PATCH',{name:'越权',parentId:null,active:true,expectedVersion:0});p1check('cross tenant department mutation denied',p1r.status===404);
p1r=await admin.request('/workspace/members/'+salesMember.id,'PATCH',{name:salesMember.displayName,departmentId:otherDept.id,roles:['SALES'],status:'ACTIVE',expectedVersion:salesMember.version});p1check('cross tenant department assignment denied',p1r.status===404);
p1r=await admin.request('/customers', 'GET',undefined,{'X-Workspace-ID':other.id});p1check('stale or forged workspace header denied',p1r.status===409&&p1r.data.code==='TENANT_CONTEXT_CHANGED');
await db`UPDATE sys_tenant SET subscription_end=now()-interval '1 day' WHERE id=${company.id}::uuid`;
p1r=await admin.request('/workspace/members','POST',{username:'expired-'+suffix,name:'到期创建',initialPassword:initial,departmentId:null,roles:['SALES']},{'Idempotency-Key':crypto.randomUUID()});p1check('expired subscription blocks organization writes',p1r.status===403);
const renewalKey=crypto.randomUUID(),renewal=await admin.ok('/workspace/renewals','POST',{months:3},{'Idempotency-Key':renewalKey});p1check('expired tenant can request renewal with quoted integer amount',renewal.amountCents===37035);
const renewalReplay=await admin.ok('/workspace/renewals','POST',{months:3},{'Idempotency-Key':renewalKey});p1check('renewal request idempotent',renewalReplay.id===renewal.id);
p1r=await admin.request('/workspace/renewals','POST',{months:1},{'Idempotency-Key':crypto.randomUUID()});p1check('only one pending renewal per tenant',p1r.status===409);
p1r=await op.request('/platform/renewals/'+renewal.id+'/decision','POST',{status:'CONFIRMED',paymentReference:'',reason:'',expectedVersion:0});p1check('manual renewal requires receipt reference',p1r.status===400);
const confirmed=await op.ok('/platform/renewals/'+renewal.id+'/decision','POST',{status:'CONFIRMED',paymentReference:'TEST-'+suffix,reason:'虚构验收，不代表实际收款',expectedVersion:0});
const confirmedReplay=await op.ok('/platform/renewals/'+renewal.id+'/decision','POST',{status:'CONFIRMED',paymentReference:'TEST-'+suffix,reason:'',expectedVersion:0});p1check('confirmation cannot extend subscription twice',confirmed.renewedUntil===confirmedReplay.renewedUntil);
const renewed=await admin.ok('/workspace/summary');p1check('expired renewal extends from current time and preserves counters',new Date(renewed.subscriptionEnd)>new Date()&&renewed.activeMemberCount===3&&renewed.customerCount===2);
const grant=await admin.ok('/workspace/support/grants','POST',{operatorUserId:op.user.id,hours:1,reason:'P1-A验收：客户只读支持',allowCredit:false,allowDescription:false},{'Idempotency-Key':crypto.randomUUID()});
const supportView=await op.ok('/platform/support/'+grant.id+'/enter','POST');op.tenant=company.id;p1check('explicit grant opens support workspace',supportView.supportTenant.id===company.id&&supportView.customerAccess.write===false);
const supportList=await op.ok('/customers');p1check('support reads scoped and masked customer data',supportList.total===2&&supportList.items.every((c:any)=>c.creditCode===null&&c.description===null&&!c.canWrite));
p1r=await op.request('/workspace/members');p1check('support does not grant organization access',p1r.status===403);
p1r=await op.request('/customers','POST',customerBody('支持越权新增',manager.id),{'Idempotency-Key':crypto.randomUUID()});p1check('support cannot write business data',p1r.status===403);
const accesses=await admin.ok('/workspace/support/grants/'+grant.id+'/access');p1check('support reads leave access audit',accesses.some((a:any)=>a.action==='CUSTOMER_LIST'));
await admin.ok('/workspace/support/grants/'+grant.id+'/revoke','POST');p1r=await op.request('/customers');p1check('support revocation effective immediately',p1r.status===403);await op.ok('/platform/support/exit','POST');op.tenant='';
const grant2=await admin.ok('/workspace/support/grants','POST',{operatorUserId:op.user.id,hours:1,reason:'过期授权验收',allowCredit:true,allowDescription:true},{'Idempotency-Key':crypto.randomUUID()});await op.ok('/platform/support/'+grant2.id+'/enter','POST');op.tenant=company.id;
masked=await op.ok('/customers/'+c1.id);p1check('support sees only explicitly authorized fields',masked.creditCode==='123456789012345678'&&masked.description==='验收敏感备注');
await db`UPDATE sys_support_grant SET expires_at=now()-interval '1 second' WHERE id=${grant2.id}::uuid`;p1r=await op.request('/customers');p1check('support expiry effective without re-login',p1r.status===403);await op.ok('/platform/support/exit','POST');op.tenant='';
await op.ok('/platform/accounts/'+salesMember.userId+'/password-reset','POST',{newPassword:initial});p1r=await sales.request('/auth/session');p1check('platform password reset invalidates all existing sessions',p1r.status===401);
const resetLogin=await sales.login(salesMember.username,initial);p1check('password reset forces change at next login',resetLogin.user.mustChangePassword===true);
const audits=await admin.ok('/workspace/audits');p1check('tenant management audit records persisted',audits.some((a:any)=>a.action==='MEMBER_CREATED')&&audits.some((a:any)=>a.action==='ROLE_POLICY_UPDATED'));
const secrets=await db`SELECT count(*)::int AS count FROM sys_platform_audit WHERE details::text LIKE ${'%'+initial+'%'} OR details::text LIKE ${'%'+changed+'%'}`;p1check('audit does not persist test passwords',secrets[0].count===0);
const counters=await db`SELECT active_member_count,customer_count,(SELECT count(*)::int FROM sys_tenant_member WHERE tenant_id=${company.id}::uuid AND status='ACTIVE') AS actual_members,(SELECT count(*)::int FROM crm_customer WHERE tenant_id=${company.id}::uuid) AS actual_customers FROM sys_tenant WHERE id=${company.id}::uuid`;
p1check('transaction counters equal actual database records',counters[0].active_member_count===counters[0].actual_members&&counters[0].customer_count===counters[0].actual_customers);
const runtimeUrl=new URL(Bun.env.DATABASE_URL!);runtimeUrl.username='crm_runtime';runtimeUrl.password=Bun.env.CRM_RUNTIME_PASSWORD!;const restricted=new SQL(runtimeUrl.toString());
const boundary=await restricted`SELECT (SELECT count(*) FROM org_department)::int AS departments,(SELECT count(*) FROM sys_role_policy)::int AS policies,(SELECT count(*) FROM sys_support_access_log)::int AS access`;
p1check('new scoped tables fail closed without context',boundary[0].departments===0&&boundary[0].policies===0&&boundary[0].access===0);await restricted.close();
const current=(await op.ok('/platform/tenants')).find((t:any)=>t.id===other.id);await op.ok('/platform/tenants/'+other.id,'PATCH',{planId:current.planId,status:'SUSPENDED',expectedVersion:current.version});p1r=await otherAdmin.request('/customers');p1check('platform suspension immediately blocks tenant access',p1r.status===403);
const currentA=(await op.ok('/platform/tenants')).find((t:any)=>t.id===company.id);await op.ok('/platform/tenants/'+company.id,'PATCH',{planId:currentA.planId,status:'SUSPENDED',expectedVersion:currentA.version});p1check('both newly created fixture tenants paused after acceptance',true);
await Promise.all([op,admin,sales,viewer,otherAdmin].map(c=>c.request('/auth/logout','POST')));await db.close();
console.log(JSON.stringify({p1aPassed:p1checks.length,checks:p1checks,fixtureTenantIds:[company.id,other.id],fixturePlanId:plan.id,scope:writeScope}));
} catch(error) { console.error(JSON.stringify({p1aFailed:true,error:String(error),completed:p1checks}));await db.close();throw error; }
