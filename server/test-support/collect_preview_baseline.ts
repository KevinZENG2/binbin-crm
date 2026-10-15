// Read-only fingerprints before/after upgrading the existing cloud preview.
// No record bodies, password hashes or credentials are emitted.
import {SQL} from 'bun';
const target=new URL(Bun.env.DATABASE_URL!);
if(Bun.env.CRM_PREVIEW_READ_SCOPE!=='crm-test-upgrade-fingerprint'||!target.hostname.endsWith('.railway.internal')||target.pathname!=='/railway')throw new Error('Private preview read scope required');
const db=new SQL(Bun.env.DATABASE_URL!);
try{
 const fingerprints:any={};
 const tables=['crm_customer','crm_customer_share','crm_contact','crm_lead','crm_opportunity','crm_opportunity_history','crm_task','crm_activity'];
 for(const table of tables){const rows=await db.unsafe('SELECT * FROM '+table+' t ORDER BY row_to_json(t)::text');fingerprints[table]={rows:rows.length,sha256:new Bun.CryptoHasher('sha256').update(JSON.stringify(rows)).digest('hex')};}
 const tenants=await db`SELECT id,code,name,status,plan_id,subscription_end,active_member_count,customer_count FROM sys_tenant ORDER BY id`;
 const accounts=await db`SELECT id,username,status,auth_version,must_change_password FROM iam_user ORDER BY id`;
 fingerprints.tenants={rows:tenants.length,sha256:new Bun.CryptoHasher('sha256').update(JSON.stringify(tenants)).digest('hex')};fingerprints.accounts={rows:accounts.length,sha256:new Bun.CryptoHasher('sha256').update(JSON.stringify(accounts)).digest('hex')};
 console.log('CRM_BASELINE '+JSON.stringify({businessWrites:false,database:'railway',fingerprints}));
}finally{await db.close();}
