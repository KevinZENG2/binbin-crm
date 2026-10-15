// Read-only checkpoint before stopping the disposable native P1-B service.
import {SQL} from 'bun';
const target=new URL(Bun.env.DATABASE_URL!);
if(Bun.env.CRM_P1B_WRITE_SCOPE!=='isolated-new-database'||target.hostname!==Bun.env.CRM_ISOLATED_DATABASE_HOST||target.pathname!=='/crm_acceptance')throw new Error('Isolated checkpoint target required');
const db=new SQL(Bun.env.DATABASE_URL!),assertions:string[]=[];
function check(name:string,ok:boolean){if(!ok)throw new Error(name);assertions.push(name);}
try {
 const engine=(await db`SELECT current_database() AS name,version() AS version`)[0];check('checkpoint is the isolated native database',engine.name==='crm_acceptance');
 const role=(await db`SELECT rolname,rolsuper,rolbypassrls FROM pg_roles WHERE rolname='crm_runtime'`)[0];check('runtime role has no superuser or RLS bypass',!role.rolsuper&&!role.rolbypassrls);
 const rls=await db`SELECT relname,relrowsecurity,relforcerowsecurity,pg_get_userbyid(relowner) AS owner FROM pg_class WHERE relname IN ('crm_customer','crm_customer_share','sys_idempotency_request','sys_audit_log','org_department','sys_role_policy','sys_support_access_log','crm_contact','crm_lead') ORDER BY relname`;check('nine scoped tables force RLS under the migration owner',rls.length===9&&rls.every((r:any)=>r.relrowsecurity&&r.relforcerowsecurity&&r.owner==='crm_migrator'));
 const tenants=await db`SELECT id,code,status,active_member_count,customer_count,(SELECT count(*)::int FROM sys_tenant_member m WHERE m.tenant_id=t.id AND status='ACTIVE') AS actual_members,(SELECT count(*)::int FROM crm_customer c WHERE c.tenant_id=t.id) AS actual_customers FROM sys_tenant t WHERE code LIKE 'p1a-%' OR code LIKE 'p1b-%' ORDER BY code`;
 check('all four newly created acceptance companies are suspended',tenants.length===4&&tenants.every((t:any)=>t.status==='SUSPENDED'));check('fixture usage counters match actual records',tenants.every((t:any)=>t.active_member_count===t.actual_members&&t.customer_count===t.actual_customers));
 const connections=(await db`SELECT count(*)::int AS total,count(*) FILTER(WHERE ssl)::int AS encrypted FROM pg_stat_ssl WHERE pid IN(SELECT pid FROM pg_stat_activity WHERE usename='crm_runtime' AND application_name='PostgreSQL JDBC Driver')`)[0];check('all Java JDBC connections use TLS',connections.total>0&&connections.total===connections.encrypted);
 const faults=(await db`SELECT count(*)::int AS count FROM pg_trigger WHERE tgname='p1b_acceptance_contact_fault'`)[0];check('injected acceptance trigger was removed',faults.count===0);
 const migrations=await db`SELECT version,description,success FROM flyway_schema_history ORDER BY installed_rank`;
 const audit=await db`SELECT action,entity_type,count(*)::int AS count FROM sys_audit_log WHERE tenant_id IN(SELECT id FROM sys_tenant WHERE code LIKE 'p1b-%') GROUP BY action,entity_type ORDER BY action`;
 console.log(JSON.stringify({checkpointPassed:assertions.length,assertions,engine,role,rls,tenants,connections,migrations,audit}));
}finally{await db.close();}
