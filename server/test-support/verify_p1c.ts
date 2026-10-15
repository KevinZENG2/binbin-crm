// P1-C native acceptance writes only to a newly bootstrapped disposable crm_acceptance database.
import {SQL} from 'bun';
if(Bun.env.CRM_P1C_WRITE_SCOPE!=='isolated-new-database')throw new Error('P1-C isolated scope required');
const target=new URL(Bun.env.DATABASE_URL!),base=Bun.env.CRM_API_URL!,apiTarget=new URL(base);
if(target.pathname!=='/crm_acceptance'||target.hostname!==Bun.env.CRM_ISOLATED_DATABASE_HOST||apiTarget.hostname!==target.hostname||apiTarget.port!=='8080'||!target.hostname.endsWith('.railway.internal'))throw new Error('Private isolated target required');
const db=new SQL(Bun.env.DATABASE_URL!),checks:string[]=[],clients:C[]=[],fixtureTenantIds:string[]=[];
const suffix=crypto.randomUUID().slice(0,8),initial='P1C-Initial-'+crypto.randomUUID(),password='P1C-Changed-'+crypto.randomUUID(),today=new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai'}).format(new Date());
function check(name:string,ok:boolean){if(!ok)throw new Error(name);checks.push(name);}
class C {
 cookie='';csrf='';tenant='';
 constructor(){clients.push(this);}
 async req(path:string,method='GET',body?:unknown,headers:Record<string,string>={}){const r=await fetch(base+'/api/v1'+path,{method,headers:{Origin:base,'X-CSRF-Token':this.csrf,...(this.cookie?{Cookie:this.cookie}:{}),...(this.tenant?{'X-Workspace-ID':this.tenant}:{}),...(body===undefined?{}:{'Content-Type':'application/json'}),...headers},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(20000)});const c=r.headers.get('Set-Cookie');if(c)this.cookie=c.split(';')[0];return{status:r.status,data:r.status===204?null:await r.json()};}
 async ok(path:string,method='GET',body?:unknown,headers?:Record<string,string>){const r=await this.req(path,method,body,headers);if(r.status<200||r.status>=300)throw new Error(path+': '+r.status+' '+r.data?.code);return r.data;}
 async login(name:string,pwd:string){const v=await this.ok('/auth/login','POST',{username:name,password:pwd});this.csrf=v.csrfToken;this.tenant=v.activeTenantId??'';return v;}
 async select(id:string){await this.ok('/auth/active-tenant','POST',{tenantId:id});this.tenant=id;}
}
const op=new C(),once=()=>({'Idempotency-Key':crypto.randomUUID()});let fault=false;
async function initialized(name:string,id:string){const c=new C();await c.login(name,initial);await c.ok('/auth/password','POST',{oldPassword:initial,newPassword:password});await c.login(name,password);await c.select(id);return c;}
async function policy(admin:C,role:string,change:any){const old=(await admin.ok('/workspace/role-policies')).find((p:any)=>p.roleCode===role);const fields=['canRead','canWrite','recordScope','creditAccess','descriptionAccess','canShare','contactCanRead','contactCanWrite','leadCanRead','leadCanWrite','opportunityCanRead','opportunityCanWrite','taskCanRead','taskCanWrite','activityCanRead','activityCanWrite'];const body:any={expectedVersion:old.version,...change};for(const k of fields)if(!(k in body))body[k]=old[k];return admin.ok('/workspace/role-policies/'+role,'PATCH',body);}
function opportunity(customer:string,owner:string,title='P1-C 虚构商机 '+suffix){return{customerId:customer,title,ownerMemberId:owner,amountCents:123456,closeDate:today,nextAction:'确认采购范围',description:'P1-C 虚构商机说明'};}
function edit(o:any,change:any={}){return{expectedVersion:o.version,title:o.title,ownerMemberId:o.ownerMemberId,amountCents:o.amountCents,closeDate:o.closeDate,nextAction:o.nextAction,description:o.description,...change};}
function taskBody(assignee:string,customer:string|null=null,opportunityId:string|null=null){return{customerId:customer,opportunityId,title:'P1-C 虚构跟进待办 '+suffix,description:'联系并确认下一步',assigneeMemberId:assignee,dueDate:today,priority:'HIGH'};}
function editTask(t:any,change:any={}){return{expectedVersion:t.version,title:t.title,description:t.description,assigneeMemberId:t.assigneeMemberId,dueDate:t.dueDate,priority:t.priority,...change};}
function activity(customer:string,opportunityId:string|null=null){return{customerId:customer,opportunityId,activityType:'CALL',content:'P1-C 虚构沟通：已确认需求，待安排方案会议',occurredAt:new Date(Date.now()-60000).toISOString()};}
function stage(o:any,to:string,change:any={}){return{expectedVersion:o.version,stage:to,reason:'P1-C 虚构阶段依据 '+to,nextAction:['WON','LOST'].includes(to)?'':'明确下一步 '+to,...change};}
try {
 const state=(await db`SELECT current_database() AS name,(SELECT count(*)::int FROM flyway_schema_history WHERE success AND version='4') AS v4`)[0];check('isolated native target has V4 migration',state.name==='crm_acceptance'&&state.v4===1);
 const anonymous=new C();for(const path of ['/opportunities','/opportunities/board','/tasks','/activities','/sales/summary'])check('anonymous denied '+path,(await anonymous.req(path)).status===401);
 await op.login('platform-operator',Bun.env.CRM_TEST_PASSWORD!);for(const path of ['/opportunities','/tasks','/activities','/sales/summary'])check('platform has no implicit business access '+path,(await op.req(path)).status===403);
 const plan=await op.ok('/platform/plans','POST',{code:'p1c-'+suffix,name:'P1-C 虚构验收套餐',monthlyPriceCents:19900,seatLimit:12,customerLimit:50,active:true,expectedVersion:0},once());
 async function company(code:string){const username=code+'-'+suffix;const t=await op.ok('/platform/tenants','POST',{code:username,name:'P1-C 验收企业 '+code+' '+suffix,industry:'虚构验收',planId:plan.id,trialDays:14,adminUsername:username,adminName:'P1-C 验收管理员',initialPassword:initial},once());fixtureTenantIds.push(t.id);const admin=await initialized(username,t.id);const s=await admin.ok('/auth/session');return{id:t.id,admin,owner:s.tenants.find((x:any)=>x.id===t.id).memberId};}
 const a=await company('p1c-a'),b=await company('p1c-b');
 const access=(await a.admin.ok('/auth/session')).businessAccess;check('session exposes P1-C module switches',access.opportunities.read&&access.opportunities.write&&access.tasks.read&&access.tasks.write&&access.activityRead&&access.activityWrite);
 async function member(name:string,roles:string[]){const username='p1c-'+name+'-'+suffix;const m=await a.admin.ok('/workspace/members','POST',{username,name:'P1-C '+name,initialPassword:initial,roles},once());return{id:m.id,user:await initialized(username,a.id)};}
 const sales=await member('sales',['SALES']),other=await member('other',['SALES']),viewer=await member('viewer',['VIEWER']),adminOnly=await member('admin',['TENANT_ADMIN']),combined=await member('combined',['SALES','VIEWER']);
 for(const path of ['/opportunities','/tasks','/activities'])check('enterprise administration alone grants no access '+path,(await adminOnly.user.req(path)).status===403);
 function customer(owner:string,name:string){return{name,industry:'制造业',level:'B',source:'虚构验收',region:'上海',ownerMemberId:owner,description:'虚构客户资料'};}
 const own=await sales.user.ok('/customers','POST',customer(sales.id,'P1-C 销售客户'),once()),otherCustomer=await other.user.ok('/customers','POST',customer(other.id,'P1-C 其他客户'),once()),managed=await a.admin.ok('/customers','POST',customer(a.owner,'P1-C 管理客户'),once()),foreign=await b.admin.ok('/customers','POST',customer(b.owner,'P1-C 跨企业客户'),once());
 const body=opportunity(own.id,sales.id),createKey=once();let o=await sales.user.ok('/opportunities','POST',body,createKey);check('opportunity starts CONTACT with expected and actual amounts separated',o.stage==='CONTACT'&&o.amountCents===123456&&o.actualAmountCents===null&&o.version===0&&o.canWrite);
 check('opportunity create is idempotent',(await sales.user.ok('/opportunities','POST',body,createKey)).id===o.id);
 check('opportunity idempotency rejects changed body',(await sales.user.req('/opportunities','POST',{...body,title:'改变内容'},createKey)).status===409);
 check('opportunity has initial immutable history',(await sales.user.ok('/opportunities/'+o.id+'/history')).items[0].toStage==='CONTACT');
 check('foreign opportunity customer hidden',(await sales.user.req('/opportunities','POST',opportunity(foreign.id,sales.id),once())).status===404);
 check('inaccessible opportunity customer hidden',(await sales.user.req('/opportunities','POST',opportunity(otherCustomer.id,sales.id),once())).status===404);
 check('foreign opportunity owner invalid',(await a.admin.req('/opportunities','POST',opportunity(managed.id,b.owner),once())).status===400);
 check('owner without linked customer access is rejected',(await a.admin.req('/opportunities','POST',opportunity(managed.id,other.id),once())).status===400);
 check('fractional cents rejected',(await sales.user.req('/opportunities','POST',{...body,amountCents:1.5},once())).status===400);
 check('negative amount rejected',(await sales.user.req('/opportunities','POST',{...body,amountCents:-1},once())).status===400);
 check('tenant injection rejected',(await sales.user.req('/opportunities','POST',{...body,tenantId:b.id},once())).status===400);
 check('opportunity stage injection rejected',(await sales.user.req('/opportunities','POST',{...body,stage:'WON'},once())).status===400);
 check('missing idempotency key rejected',(await sales.user.req('/opportunities','POST',body)).status===400);
 check('csrf protects P1-C writes',(await sales.user.req('/opportunities','POST',body,{'Idempotency-Key':crypto.randomUUID(),'X-CSRF-Token':'invalid'})).status===403);
 check('workspace mismatch blocks P1-C access',(await sales.user.req('/opportunities','GET',undefined,{'X-Workspace-ID':b.id})).status===409);
 check('opportunity date outside range rejected',(await sales.user.req('/opportunities','POST',{...body,closeDate:'1900-01-01'},once())).status===400);
 check('viewer cannot create opportunity',(await viewer.user.req('/opportunities','POST',body,once())).status===403);
 check('viewer reads opportunity without write',(await viewer.user.ok('/opportunities/'+o.id)).canWrite===false&&(await viewer.user.ok('/opportunities/'+o.id)).canLink===false);
 const otherOpp=await other.user.ok('/opportunities','POST',opportunity(otherCustomer.id,other.id),once());check('sales own scope hides other opportunity',(await sales.user.req('/opportunities/'+otherOpp.id)).status===404);
 check('foreign opportunity and history hidden',(await b.admin.req('/opportunities/'+o.id)).status===404&&(await b.admin.req('/opportunities/'+o.id+'/history')).status===404);
 check('opportunity query bounds validated',(await sales.user.req('/opportunities?pageSize=101')).status===400&&(await sales.user.req('/opportunities?stage=UNKNOWN')).status===400);
 o=await sales.user.ok('/opportunities/'+o.id,'PATCH',edit(o,{title:'P1-C 更新后的商机',amountCents:200001}));check('opportunity edit persists cents and version',o.version===1&&o.amountCents===200001);
 check('stale opportunity edit rejected',(await sales.user.req('/opportunities/'+o.id,'PATCH',edit(o,{expectedVersion:0}))).status===409);
 check('opportunity edit needs version',(await sales.user.req('/opportunities/'+o.id,'PATCH',{title:o.title,ownerMemberId:sales.id,amountCents:o.amountCents,closeDate:today,nextAction:'下步'})).status===400);
 check('stage skip rejected',(await sales.user.req('/opportunities/'+o.id+'/stage','POST',stage(o,'READY'),once())).status===400);
 check('stage reason required',(await sales.user.req('/opportunities/'+o.id+'/stage','POST',stage(o,'NEEDS',{reason:' '}),once())).status===400);
 check('open stage next action required',(await sales.user.req('/opportunities/'+o.id+'/stage','POST',stage(o,'NEEDS',{nextAction:''}),once())).status===400);
 const stageBody=stage(o,'NEEDS'),stageKey=once();o=await sales.user.ok('/opportunities/'+o.id+'/stage','POST',stageBody,stageKey);check('stage moves and saves history in one transaction',o.stage==='NEEDS'&&o.version===2&&(await sales.user.ok('/opportunities/'+o.id+'/history')).total===2);
 check('stage same-key replay has no additional history',(await sales.user.ok('/opportunities/'+o.id+'/stage','POST',stageBody,stageKey)).version===2&&(await sales.user.ok('/opportunities/'+o.id+'/history')).total===2);
 check('stage replay rejects a different target',(await sales.user.req('/opportunities/'+o.id+'/stage','POST',{...stageBody,stage:'SOLUTION'},stageKey)).status===409);
 check('stale stage change rejected',(await sales.user.req('/opportunities/'+o.id+'/stage','POST',stage(o,'SOLUTION',{expectedVersion:1}),once())).status===409);
 const race=await Promise.all([sales.user.req('/opportunities/'+o.id+'/stage','POST',stage(o,'SOLUTION'),once()),sales.user.req('/opportunities/'+o.id+'/stage','POST',stage(o,'CONTACT'),once())]);check('two different stage requests have one winner and one version conflict',race.filter(x=>x.status===200).length===1&&race.filter(x=>x.status===409).length===1);o=await sales.user.ok('/opportunities/'+o.id);check('concurrent stage requests create one history row',(await sales.user.ok('/opportunities/'+o.id+'/history')).total===3);
 while(o.stage!=='READY'){const next=SalesNext(o.stage);o=await sales.user.ok('/opportunities/'+o.id+'/stage','POST',stage(o,next),once());}
 check('won requires actual amount',(await sales.user.req('/opportunities/'+o.id+'/stage','POST',stage(o,'WON'),once())).status===400);
 const wonBody=stage(o,'WON',{actualAmountCents:180000}),wonKey=once();o=await sales.user.ok('/opportunities/'+o.id+'/stage','POST',wonBody,wonKey);check('won preserves estimate and separate actual amount',o.stage==='WON'&&o.amountCents===200001&&o.actualAmountCents===180000&&o.closedAt&&!o.canWrite&&o.canLink);
 check('won same-key retry is safe',(await sales.user.ok('/opportunities/'+o.id+'/stage','POST',wonBody,wonKey)).version===o.version);
 check('closed opportunity cannot be edited or reopened',(await sales.user.req('/opportunities/'+o.id,'PATCH',edit(o,{nextAction:'不可更改'}))).status===409&&(await sales.user.req('/opportunities/'+o.id+'/stage','POST',stage(o,'READY'),once())).status===409);
 let lost=await sales.user.ok('/opportunities','POST',opportunity(own.id,sales.id,'P1-C 丢单示例'),once());lost=await sales.user.ok('/opportunities/'+lost.id+'/stage','POST',stage(lost,'LOST',{reason:'预算取消'}),once());check('lost records reason and no actual amount',lost.stage==='LOST'&&lost.actualAmountCents===null&&(await sales.user.ok('/opportunities/'+lost.id+'/history')).items[0].reason==='预算取消');
 const board=await sales.user.ok('/opportunities/board');check('board includes seven stages with permission-filtered totals',board.length===7&&board.find((s:any)=>s.stage==='WON').actualAmountCents===180000&&board.reduce((n:number,s:any)=>n+s.total,0)===2);
 check('board and list filters agree',(await sales.user.ok('/opportunities?stage=WON')).total===board.find((s:any)=>s.stage==='WON').total);
 const managerBoard=await a.admin.ok('/opportunities/board');check('manager sees all visible opportunity totals',managerBoard.reduce((n:number,s:any)=>n+s.total,0)===3);
 let t=await sales.user.ok('/tasks','POST',taskBody(sales.id),once());check('independent task is supported',t.customerId===null&&t.opportunityId===null&&t.status==='PENDING'&&t.canWrite);
 const tk=once(),tb=taskBody(sales.id,own.id,o.id);let linked=await sales.user.ok('/tasks','POST',tb,tk);check('linked task creation replays safely',(await sales.user.ok('/tasks','POST',tb,tk)).id===linked.id);
 check('task cannot mix customer and opportunity',(await sales.user.req('/tasks','POST',taskBody(sales.id,own.id,otherOpp.id),once())).status===404);
 check('task requires customer with opportunity',(await sales.user.req('/tasks','POST',taskBody(sales.id,null,o.id),once())).status===400);
 check('foreign task links hidden',(await sales.user.req('/tasks','POST',taskBody(sales.id,foreign.id),once())).status===404);
 check('foreign task assignee rejected',(await a.admin.req('/tasks','POST',taskBody(b.owner),once())).status===400);
 check('task assigned to inaccessible recipient rejected',(await a.admin.req('/tasks','POST',taskBody(other.id,managed.id),once())).status===400);
 check('viewer cannot create task',(await viewer.user.req('/tasks','POST',taskBody(viewer.id),once())).status===403);
 check('task deadline same Shanghai day is not overdue',!t.overdue);
 t=await sales.user.ok('/tasks/'+t.id,'PATCH',editTask(t,{dueDate:'2000-01-01',priority:'LOW'}));check('task editing and overdue calculation persist',t.version===1&&t.overdue&&t.priority==='LOW');
 check('stale task update rejected',(await sales.user.req('/tasks/'+t.id,'PATCH',editTask(t,{expectedVersion:0}))).status===409);
 check('overdue and own task filters are scoped',(await sales.user.ok('/tasks?status=OVERDUE&mine=true')).items.some((x:any)=>x.id===t.id));
 const completeKey=once(),version={expectedVersion:t.version};t=await sales.user.ok('/tasks/'+t.id+'/complete','POST',version,completeKey);check('task completion records time and increments version',t.status==='DONE'&&t.completedAt&&t.version===2&&!t.overdue);
 check('task completion replay is stable',(await sales.user.ok('/tasks/'+t.id+'/complete','POST',version,completeKey)).version===2);
 check('completed task cannot be edited directly',(await sales.user.req('/tasks/'+t.id,'PATCH',editTask(t))).status===409);
 check('reopen requires current version',(await sales.user.req('/tasks/'+t.id+'/reopen','POST',{expectedVersion:0},once())).status===409);
 t=await sales.user.ok('/tasks/'+t.id+'/reopen','POST',{expectedVersion:t.version},once());check('task can reopen with completion time cleared',t.status==='PENDING'&&t.completedAt===null&&t.version===3);
 const taskRace=await Promise.all([sales.user.req('/tasks/'+t.id+'/complete','POST',{expectedVersion:t.version},once()),sales.user.req('/tasks/'+t.id+'/complete','POST',{expectedVersion:t.version},once())]);check('concurrent task completion has one winner',taskRace.filter(x=>x.status===200).length===1&&taskRace.filter(x=>x.status===409).length===1);
 check('sales cannot read another assignee independent task',(await other.user.req('/tasks/'+t.id)).status===404);check('foreign task detail hidden',(await b.admin.req('/tasks/'+t.id)).status===404);
 const acBody=activity(own.id,o.id),acKey=once();const ac=await sales.user.ok('/activities','POST',acBody,acKey);check('activity links customer and closed opportunity',ac.customerId===own.id&&ac.opportunityId===o.id&&ac.content===acBody.content);
 check('activity creation is idempotent',(await sales.user.ok('/activities','POST',acBody,acKey)).id===ac.id);
 check('activity content conflict rejected',(await sales.user.req('/activities','POST',{...acBody,content:'改变内容'},acKey)).status===409);
 check('activity blank content rejected',(await sales.user.req('/activities','POST',{...acBody,content:' '},once())).status===400);
 check('future activity time rejected',(await sales.user.req('/activities','POST',{...acBody,occurredAt:'2099-01-01T00:00:00Z'},once())).status===400);
 check('activity type validated',(await sales.user.req('/activities','POST',{...acBody,activityType:'UNKNOWN'},once())).status===400);
 check('cross-tenant activity customer hidden',(await sales.user.req('/activities','POST',activity(foreign.id),once())).status===404);
 check('viewer cannot add followup',(await viewer.user.req('/activities','POST',acBody,once())).status===403);
 check('activity and task opportunity filters retain links',(await sales.user.ok('/activities?opportunityId='+o.id)).items.some((x:any)=>x.id===ac.id)&&(await sales.user.ok('/tasks?opportunityId='+o.id+'&status=')).items.some((x:any)=>x.id===linked.id));
 check('foreign activity detail hidden',(await b.admin.req('/activities/'+ac.id)).status===404);
 const initialSummary=await sales.user.ok('/sales/summary');check('workbench summary uses actor scope',initialSummary.customers===1&&initialSummary.openOpportunities===0&&initialSummary.myPendingTasks===1&&initialSummary.myOverdueTasks===0);
 await policy(a.admin,'SALES',{opportunityCanRead:false,opportunityCanWrite:false});check('opportunity revocation immediately denies module and hides linked tasks/followups',(await sales.user.req('/opportunities')).status===403&&(await sales.user.req('/tasks/'+linked.id)).status===404&&(await sales.user.req('/activities/'+ac.id)).status===404);
 check('independent task survives opportunity revocation',(await sales.user.req('/tasks/'+t.id)).status===200);
 await policy(a.admin,'SALES',{opportunityCanRead:true,opportunityCanWrite:true,taskCanRead:false,taskCanWrite:false});check('task switch is independent',(await sales.user.req('/tasks')).status===403&&(await sales.user.req('/opportunities')).status===200);
 await policy(a.admin,'SALES',{taskCanRead:true,taskCanWrite:true,activityCanRead:false,activityCanWrite:false});check('activity switch is independent',(await sales.user.req('/activities')).status===403&&(await sales.user.req('/tasks')).status===200);await policy(a.admin,'SALES',{activityCanRead:true,activityCanWrite:true});
 const vp=(await a.admin.ok('/workspace/role-policies')).find((p:any)=>p.roleCode==='VIEWER');check('viewer P1-C writes rejected by policy validation',(await a.admin.req('/workspace/role-policies/VIEWER','PATCH',{canRead:true,canWrite:false,recordScope:'ALL',creditAccess:'NONE',descriptionAccess:'READ',canShare:false,expectedVersion:vp.version,taskCanRead:true,taskCanWrite:true})).status===400);
 // A customer share alone must not share other people's opportunities.
 await a.admin.ok('/customers/'+managed.id+'/shares','POST',{memberId:sales.id,canWrite:false,expiresAt:new Date(Date.now()+3600000).toISOString()});
 const sharedOwn=await a.admin.ok('/opportunities','POST',opportunity(managed.id,sales.id,'P1-C 受控共享商机'),once()),sharedOther=await a.admin.ok('/opportunities','POST',opportunity(managed.id,a.owner,'P1-C 未共享商机'),once());
 check('customer share does not grant all opportunity records',(await sales.user.req('/opportunities/'+sharedOther.id)).status===404);
 check('read-only customer share makes owned opportunity readonly',!(await sales.user.ok('/opportunities/'+sharedOwn.id)).canWrite);
 check('read-only shared opportunity stage write rejected',(await sales.user.req('/opportunities/'+sharedOwn.id+'/stage','POST',stage(sharedOwn,'NEEDS'),once())).status===403);
 await a.admin.ok('/customers/'+managed.id+'/shares','POST',{memberId:sales.id,canWrite:true,expiresAt:new Date(Date.now()+3600000).toISOString()});check('customer write share enables own opportunity writes',(await sales.user.ok('/opportunities/'+sharedOwn.id)).canWrite);
 const sharedActivity=await sales.user.ok('/activities','POST',activity(managed.id,sharedOwn.id),once()),sharedTask=await sales.user.ok('/tasks','POST',taskBody(sales.id,managed.id,sharedOwn.id),once());
 await a.admin.ok('/customers/'+managed.id+'/shares/'+sales.id,'DELETE');check('customer share revocation hides opportunity history and linked content',(await sales.user.req('/opportunities/'+sharedOwn.id)).status===404&&(await sales.user.req('/opportunities/'+sharedOwn.id+'/history')).status===404&&(await sales.user.req('/activities/'+sharedActivity.id)).status===404&&(await sales.user.req('/tasks/'+sharedTask.id)).status===404);
 // Converted source lead is fixed and must belong to the opportunity customer.
 let l=await sales.user.ok('/leads','POST',{name:'P1-C 来源联系人',companyName:'P1-C 来源公司',industry:'制造业',source:'虚构验收',ownerMemberId:sales.id},once());
 for(const state of ['CONTACTED','QUALIFIED'])l=await sales.user.ok('/leads/'+l.id,'PATCH',{expectedVersion:l.version,name:l.name,companyName:l.companyName,industry:l.industry,source:l.source,ownerMemberId:sales.id,status:state});
 check('unconverted source lead rejected',(await sales.user.req('/opportunities','POST',{...body,sourceLeadId:l.id},once())).status===400);
 l=await sales.user.ok('/leads/'+l.id+'/convert','POST',{expectedVersion:l.version,targetCustomerId:own.id,primaryContact:false},once());const fromLead=await sales.user.ok('/opportunities','POST',{...body,sourceLeadId:l.id,title:'P1-C 来源商机'},once());check('opportunity preserves converted lead source',fromLead.sourceLeadId===l.id);
 check('source lead cannot link another customer',(await a.admin.req('/opportunities','POST',{...opportunity(managed.id,a.owner),sourceLeadId:l.id},once())).status===400);
 await policy(a.admin,'SALES',{leadCanRead:false,leadCanWrite:false});const masked=await sales.user.ok('/opportunities/'+fromLead.id);check('source lead id and name are masked after lead access revocation',masked.sourceLeadId===null&&masked.sourceLeadName===null);await policy(a.admin,'SALES',{leadCanRead:true,leadCanWrite:true});
 // Department and multi-role read/write scopes remain separate.
 const d=await a.admin.ok('/workspace/departments','POST',{name:'P1-C 测试销售部',active:true,expectedVersion:0},once());
 const members=await a.admin.ok('/workspace/members');for(const who of [sales,other]){const m=members.find((x:any)=>x.id===who.id);await a.admin.ok('/workspace/members/'+m.id,'PATCH',{name:m.displayName,departmentId:d.id,roles:m.roles,status:'ACTIVE',expectedVersion:m.version});}
 await policy(a.admin,'SALES',{recordScope:'DEPARTMENT'});check('same department opportunity is visible',(await sales.user.req('/opportunities/'+otherOpp.id)).status===200);
 await policy(a.admin,'SALES',{recordScope:'OWN_SHARED'});check('department scope removal immediately hides others',(await sales.user.req('/opportunities/'+otherOpp.id)).status===404);
 check('combined viewer scope broadens read without broadening write',!(await combined.user.ok('/opportunities/'+otherOpp.id)).canWrite&&(await combined.user.req('/opportunities/'+otherOpp.id+'/stage','POST',stage(otherOpp,'NEEDS'),once())).status===403);
 // Inject a history insert failure: stage, history, audit and request state must roll back.
 const failureOpp=await sales.user.ok('/opportunities','POST',opportunity(own.id,sales.id,'P1-C 事务故障验收'),once()),failureKey=crypto.randomUUID();
 await db.unsafe(`CREATE FUNCTION p1c_acceptance_history_fault_fn() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.opportunity_id='${failureOpp.id}'::uuid AND NEW.business_version>0 THEN RAISE EXCEPTION 'isolated P1-C history fault'; END IF; RETURN NEW; END $$; CREATE TRIGGER p1c_acceptance_history_fault BEFORE INSERT ON crm_opportunity_history FOR EACH ROW EXECUTE FUNCTION p1c_acceptance_history_fault_fn()`);fault=true;
 const before=(await db`SELECT count(*)::int AS count FROM sys_audit_log WHERE tenant_id=${a.id}::uuid AND entity_id=${failureOpp.id}::uuid`)[0].count;
 const failed=await sales.user.req('/opportunities/'+failureOpp.id+'/stage','POST',stage(failureOpp,'NEEDS'),{'Idempotency-Key':failureKey});check('history insert failure aborts stage transaction',failed.status===503&&failed.data.code==='SERVICE_UNAVAILABLE'&&(await sales.user.ok('/opportunities/'+failureOpp.id)).stage==='CONTACT'&&(await sales.user.ok('/opportunities/'+failureOpp.id+'/history')).total===1);
 const rolled=(await db`SELECT (SELECT count(*)::int FROM sys_audit_log WHERE tenant_id=${a.id}::uuid AND entity_id=${failureOpp.id}::uuid) AS audits,(SELECT count(*)::int FROM sys_idempotency_request WHERE tenant_id=${a.id}::uuid AND request_key=${failureKey}) AS requests`)[0];check('history failure rolls back audit and idempotency entries',rolled.audits===before&&rolled.requests===0);
 await db.unsafe('DROP TRIGGER p1c_acceptance_history_fault ON crm_opportunity_history; DROP FUNCTION p1c_acceptance_history_fault_fn()');fault=false;
 check('failed stage request can retry with same key',(await sales.user.ok('/opportunities/'+failureOpp.id+'/stage','POST',stage(failureOpp,'NEEDS'),{'Idempotency-Key':failureKey})).stage==='NEEDS');
 // Native RLS and foreign keys are enforced independently of HTTP.
 const tables=await db`SELECT relname,relrowsecurity,relforcerowsecurity,pg_get_userbyid(relowner) AS owner FROM pg_class WHERE relname IN ('crm_customer','crm_customer_share','sys_idempotency_request','sys_audit_log','org_department','sys_role_policy','sys_support_access_log','crm_contact','crm_lead','crm_opportunity','crm_opportunity_history','crm_activity','crm_task')`;
 check('all thirteen scoped tables force RLS with separate owner',tables.length===13&&tables.every((x:any)=>x.relrowsecurity&&x.relforcerowsecurity&&x.owner==='crm_migrator'));
 const limitedUrl=new URL(Bun.env.DATABASE_URL!);limitedUrl.username='crm_runtime';limitedUrl.password=Bun.env.CRM_RUNTIME_PASSWORD!;const limited=new SQL(limitedUrl.toString());
 try{const closed=(await limited`SELECT (SELECT count(*)::int FROM crm_opportunity) AS o,(SELECT count(*)::int FROM crm_opportunity_history) AS h,(SELECT count(*)::int FROM crm_task) AS t,(SELECT count(*)::int FROM crm_activity) AS a`)[0];check('P1-C tables fail closed without tenant context',Object.values(closed).every(v=>v===0));
  let refused=false;try{await limited.begin(async tx=>{await tx`SELECT set_config('crm.tenant_id',${a.id},true)`;await tx`UPDATE crm_opportunity_history SET reason='不可更改' WHERE opportunity_id=${o.id}::uuid`;});}catch{refused=true;}check('runtime cannot update immutable opportunity history',refused);
  refused=false;try{await limited.begin(async tx=>{await tx`SELECT set_config('crm.tenant_id',${a.id},true)`;await tx`UPDATE crm_activity SET content='不可更改' WHERE id=${ac.id}::uuid`;});}catch{refused=true;}check('runtime cannot update original activities',refused);
  refused=false;try{await limited.begin(async tx=>{await tx`SELECT set_config('crm.tenant_id',${a.id},true)`;await tx`INSERT INTO crm_task(id,tenant_id,title,assignee_member_id,created_by_member_id,due_date,priority) VALUES(${crypto.randomUUID()}::uuid,${b.id}::uuid,'跨企业注入',${sales.id}::uuid,${sales.id}::uuid,${today}::date,'NORMAL')`;});}catch{refused=true;}check('RLS rejects wrong-tenant task inserts',refused);
  refused=false;try{await limited.begin(async tx=>{await tx`SELECT set_config('crm.tenant_id',${a.id},true)`;await tx`INSERT INTO crm_activity(id,tenant_id,customer_id,opportunity_id,activity_type,content,occurred_at,actor_member_id) VALUES(${crypto.randomUUID()}::uuid,${a.id}::uuid,${managed.id}::uuid,${o.id}::uuid,'CALL','关联不匹配',now(),${sales.id}::uuid)`;});}catch{refused=true;}check('composite FK rejects mismatched activity customer/opportunity',refused);
  refused=false;try{await limited.begin(async tx=>{await tx`SELECT set_config('crm.tenant_id',${a.id},true)`;await tx`INSERT INTO crm_opportunity(id,tenant_id,customer_id,source_lead_id,title,owner_member_id,amount_cents,close_date,next_action) VALUES(${crypto.randomUUID()}::uuid,${a.id}::uuid,${managed.id}::uuid,${l.id}::uuid,'错配来源',${a.owner}::uuid,100,${today}::date,'跟进')`;});}catch{refused=true;}check('composite FK rejects mismatched converted source lead',refused);
 }finally{await limited.close();}
 const connections=(await db`SELECT count(*)::int AS total,count(*) FILTER(WHERE ssl)::int AS encrypted FROM pg_stat_ssl WHERE pid IN(SELECT pid FROM pg_stat_activity WHERE usename='crm_runtime' AND application_name='PostgreSQL JDBC Driver')`)[0];check('all Java JDBC connections use TLS',connections.total>0&&connections.total===connections.encrypted);
 const operators=await a.admin.ok('/workspace/support/operators'),operator=operators.find((x:any)=>x.username==='platform-operator');const grant=await a.admin.ok('/workspace/support/grants','POST',{operatorUserId:operator.id,hours:1,reason:'P1-C 受控支持验收',allowCredit:false,allowDescription:false},once());await op.ok('/platform/support/'+grant.id+'/enter','POST');op.tenant=a.id;
 for(const path of ['/opportunities','/tasks','/activities','/sales/summary'])check('customer-only support grant excludes '+path,(await op.req(path)).status===403);await op.ok('/platform/support/exit','POST');op.tenant='';
 await db`UPDATE sys_tenant SET subscription_end=now()-interval '1 day' WHERE id=${a.id}::uuid`;
 check('expired subscription retains controlled read',(await sales.user.req('/opportunities')).status===200&&(await sales.user.req('/tasks')).status===200&&(await sales.user.req('/activities')).status===200);
 check('expired subscription denies all P1-C writes',(await sales.user.req('/opportunities','POST',body,once())).status===403&&(await sales.user.req('/tasks','POST',taskBody(sales.id),once())).status===403&&(await sales.user.req('/activities','POST',activity(own.id),once())).status===403);
 check('expired record responses remove write actions',(await sales.user.ok('/opportunities/'+fromLead.id)).canWrite===false&&(await sales.user.ok('/tasks/'+linked.id)).canWrite===false);
 for(const id of fixtureTenantIds)await db`UPDATE sys_tenant SET status='SUSPENDED' WHERE id=${id}::uuid`;
 check('suspended company denies P1-C business reads',(await sales.user.req('/opportunities')).status===403&&(await sales.user.req('/tasks')).status===403&&(await sales.user.req('/activities')).status===403);
 const audit=await db`SELECT action,entity_type,count(*)::int AS count FROM sys_audit_log WHERE tenant_id IN (${a.id}::uuid,${b.id}::uuid) GROUP BY action,entity_type ORDER BY action`;
 check('audit records all new business actions',['OPPORTUNITY_CREATED','OPPORTUNITY_UPDATED','OPPORTUNITY_STAGE_CHANGED','TASK_CREATED','TASK_UPDATED','TASK_COMPLETED','TASK_REOPENED','ACTIVITY_CREATED'].every(action=>audit.some((x:any)=>x.action===action)));
 console.log('CRM_RESULT '+JSON.stringify({phase:'P1-C',passed:checks.length,checks,fixtureTenantIds,fixturePlanId:plan.id,fixturesPaused:true,connections,audit,concurrency:'two requests only; not a load test'}));
}finally{
 if(fault)await db.unsafe('DROP TRIGGER IF EXISTS p1c_acceptance_history_fault ON crm_opportunity_history; DROP FUNCTION IF EXISTS p1c_acceptance_history_fault_fn()').catch(()=>undefined);
 for(const id of fixtureTenantIds)await db`UPDATE sys_tenant SET status='SUSPENDED' WHERE id=${id}::uuid`.catch(()=>undefined);
 await Promise.all(clients.filter(c=>c.cookie).map(c=>c.req('/auth/logout','POST').catch(()=>undefined)));await db.close();
}
function SalesNext(stage:string){return ['CONTACT','NEEDS','SOLUTION','NEGOTIATION','READY'][['CONTACT','NEEDS','SOLUTION','NEGOTIATION','READY'].indexOf(stage)+1];}
