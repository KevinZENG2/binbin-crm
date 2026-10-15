// One-off Railway verification against native PostgreSQL. No HTTP listener or durable worker.
import { SQL } from 'bun';
const admin=new SQL(Bun.env.DATABASE_URL!);
const url=new URL(Bun.env.DATABASE_URL!);url.username='crm_runtime';url.password=Bun.env.CRM_RUNTIME_PASSWORD!;
const runtime=new SQL(url.toString());
const tenant=(n:number)=>`00000000-0000-4000-8000-${String(n).padStart(12,'0')}`;
const checks:string[]=[];
function check(label:string,value:boolean){if(!value)throw new Error(label);checks.push(label);}
async function rejected(label:string,state:string,fn:()=>Promise<unknown>){let rejected=false;try{await fn();}catch(e){const x=e as {errno?:string;code?:string};if(String(x.errno||x.code).includes(state))rejected=true;else throw new Error(label+': unexpected SQL state '+String(x.errno||x.code));}check(label,rejected);}
const role=await runtime`SELECT current_user AS name,rolsuper,rolbypassrls FROM pg_roles WHERE rolname=current_user`;
check('runtime is a non-superuser without BYPASSRLS',role[0].name==='crm_runtime'&&!role[0].rolsuper&&!role[0].rolbypassrls);
const tables=await runtime`SELECT relname,relrowsecurity,relforcerowsecurity,pg_get_userbyid(relowner) AS owner FROM pg_class WHERE relname IN ('crm_customer','crm_customer_share','sys_idempotency_request','sys_audit_log')`;
check('four business tables force RLS and have a separate owner',tables.length===4&&tables.every((r:any)=>r.relrowsecurity&&r.relforcerowsecurity&&r.owner==='crm_migrator'));
const missing=await runtime`SELECT count(*)::int AS count FROM crm_customer`;
check('missing tenant context reveals zero rows',missing[0].count===0);
await runtime.begin(async tx=>{
 await tx`SELECT set_config('crm.tenant_id',${tenant(2)},true)`;
 const records=await tx`SELECT id FROM crm_customer`;
 check('RLS alone returns only tenant B',records.length===1&&records[0].id===tenant(304));
 const foreign=await tx`SELECT id FROM crm_customer WHERE id=${tenant(301)}::uuid`;
 check('explicit foreign ID cannot bypass RLS',foreign.length===0);
});
const reset=await runtime`SELECT count(*)::int AS count FROM crm_customer`;
check('transaction context clears after commit',reset[0].count===0);
await rejected('RLS rejects foreign tenant writes','42501',()=>runtime.begin(async tx=>{
 await tx`SELECT set_config('crm.tenant_id',${tenant(1)},true)`;
 await tx`INSERT INTO crm_customer(id,tenant_id,customer_name,industry,level_code,source_code,owner_member_id) VALUES(${crypto.randomUUID()}::uuid,${tenant(2)}::uuid,'RLS test','Test','B','Test',${tenant(210)}::uuid)`;
}));
await rejected('composite foreign key rejects cross tenant owner','23503',()=>runtime.begin(async tx=>{
 await tx`SELECT set_config('crm.tenant_id',${tenant(1)},true)`;
 await tx`INSERT INTO crm_customer(id,tenant_id,customer_name,industry,level_code,source_code,owner_member_id) VALUES(${crypto.randomUUID()}::uuid,${tenant(1)}::uuid,'FK test','Test','B','Test',${tenant(210)}::uuid)`;
}));
await rejected('audit log is append only for runtime role','42501',()=>runtime.unsafe("UPDATE sys_audit_log SET action='CHANGED'"));
await rejected('runtime cannot remove customer records','42501',()=>runtime.unsafe('DELETE FROM crm_customer'));
await rejected('runtime cannot modify identity roles','42501',()=>runtime.unsafe("UPDATE sys_member_role SET role_code='SALES_MANAGER'"));
const stored=await admin`SELECT count(*)::int AS count FROM auth_session WHERE length(token_hash)<>64 OR token_hash !~ '^[a-f0-9]{64}$'`;
check('sessions store token digests',stored[0].count===0);
const audits=await admin`SELECT count(*)::int AS count FROM sys_audit_log WHERE action IN ('CUSTOMER_CREATED','CUSTOMER_UPDATED')`;
check('customer writes produce database audit records',audits[0].count>=1);
const encrypted=await admin`SELECT count(*)::int AS count FROM pg_stat_activity a JOIN pg_stat_ssl s ON s.pid=a.pid WHERE a.usename='crm_runtime' AND s.ssl`;
check('Java runtime database connections use TLS',encrypted[0].count>=1);
const memberClient=new Client();await memberClient.login('alice');await memberClient.select(1);
try{
 await admin`UPDATE sys_tenant_member SET status='DISABLED' WHERE id=${tenant(201)}::uuid`;
 const response=await memberClient.req('/customers');check('disabled membership invalidates selected workspace',response.status===404);
}finally{await admin`UPDATE sys_tenant_member SET status='ACTIVE' WHERE id=${tenant(201)}::uuid`;}
try{
 await admin`DELETE FROM sys_member_role WHERE tenant_id=${tenant(1)}::uuid AND member_id=${tenant(201)}::uuid AND role_code='SALES'`;
 const response=await memberClient.req('/customers');check('role withdrawal is effective on the next request',response.status===403);
}finally{await admin`INSERT INTO sys_member_role(tenant_id,member_id,role_code) VALUES(${tenant(1)}::uuid,${tenant(201)}::uuid,'SALES') ON CONFLICT DO NOTHING`;}
const revokedClient=new Client();await revokedClient.login('bob');
await admin`UPDATE iam_user SET auth_version=auth_version+1 WHERE username='bob'`;
const revokedResponse=await revokedClient.req('/auth/session');check('auth version change revokes existing session',revokedResponse.status===401);
const left=new Client(),right=new Client();await left.login('owner');await right.login('owner');await left.select(1);await right.select(2);
let separated=true;
for(let i=0;i<4;i++){
 const [a,b]=await Promise.all([left.req('/customers'),right.req('/customers')]);
 separated=separated&&a.status===200&&b.status===200&&a.data.total>=4&&b.data.total===1&&b.data.items[0].id===tenant(304)&&!a.data.items.some((x:any)=>x.id===tenant(304));
}
check('parallel sessions preserve tenant boundaries through connection pool',separated);
try{
 await admin`UPDATE sys_tenant SET status='SUSPENDED' WHERE id=${tenant(2)}::uuid`;
 const response=await right.req('/customers');check('suspended tenant blocks business reads',response.status===403);
}finally{await admin`UPDATE sys_tenant SET status='ACTIVE' WHERE id=${tenant(2)}::uuid`;}
await Promise.all([memberClient,left,right].map(c=>c.req('/auth/logout','POST')));
console.log(JSON.stringify({passed:checks.length,checks}));
await runtime.close();await admin.close();
