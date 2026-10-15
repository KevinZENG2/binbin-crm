// Read-only checkpoint after the explicitly authorized isolated acceptance suite.
import {SQL} from 'bun';
const target=new URL(Bun.env.DATABASE_URL!);
if(Bun.env.CRM_P1A_WRITE_SCOPE!=='isolated-new-database'||target.pathname!=='/crm_acceptance'||target.hostname!==Bun.env.CRM_ISOLATED_DATABASE_HOST)throw new Error('Isolated checkpoint target mismatch');
const sql=new SQL(Bun.env.DATABASE_URL!);
const engine=await sql`SELECT current_database() AS database,current_setting('server_version') AS version`;
if(engine[0].database!=='crm_acceptance')throw new Error('Isolated checkpoint database mismatch');
const role=await sql`SELECT rolname,rolsuper,rolbypassrls FROM pg_roles WHERE rolname='crm_runtime'`;
const rls=await sql`SELECT c.relname,c.relrowsecurity,c.relforcerowsecurity,c.relowner::regrole::text AS owner FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relname IN ('crm_customer','crm_customer_share','sys_idempotency_request','sys_audit_log','org_department','sys_role_policy','sys_support_access_log') ORDER BY c.relname`;
const connections=await sql`SELECT count(*)::int AS total,count(*) FILTER(WHERE s.ssl)::int AS tls FROM pg_stat_activity a LEFT JOIN pg_stat_ssl s ON s.pid=a.pid WHERE a.usename='crm_runtime' AND a.application_name='PostgreSQL JDBC Driver'`;
const tenants=await sql`SELECT t.id,t.code,t.status,t.active_member_count,t.customer_count,(SELECT count(*)::int FROM sys_tenant_member m WHERE m.tenant_id=t.id AND m.status='ACTIVE') AS actual_members,(SELECT count(*)::int FROM crm_customer c WHERE c.tenant_id=t.id) AS actual_customers FROM sys_tenant t WHERE t.code LIKE 'p1a-%' ORDER BY t.code`;
const platformAudit=await sql`SELECT tenant_id,action,count(*)::int AS count FROM sys_platform_audit GROUP BY tenant_id,action ORDER BY tenant_id,action`;
const supportAudit=await sql`SELECT tenant_id,action,count(*)::int AS count FROM sys_support_access_log GROUP BY tenant_id,action ORDER BY tenant_id,action`;
const renewals=await sql`SELECT tenant_id,status,months,amount_cents::text AS amount_cents,version,renewed_from,renewed_until FROM sys_subscription_request ORDER BY created_at`;
const assertions={runtimeNotSuperuser:role.length===1&&!role[0].rolsuper&&!role[0].rolbypassrls,sevenTablesForceRls:rls.length===7&&rls.every(r=>r.relrowsecurity&&r.relforcerowsecurity&&r.owner!=='crm_runtime'),jdbcUsesTls:connections[0].total>0&&connections[0].total===connections[0].tls,bothFixturesSuspended:tenants.length===2&&tenants.every(t=>t.status==='SUSPENDED'),fixtureCountersMatch:tenants.every(t=>t.active_member_count===t.actual_members&&t.customer_count===t.actual_customers)};
if(Object.values(assertions).some(value=>!value))throw new Error('Checkpoint invariant failed');
console.log(JSON.stringify({checkpoint:'passed',engine:engine[0],assertions,role:role[0],rls,connections:connections[0],fixtureTenants:tenants,platformAudit,supportAudit,renewals}));
await sql.close();
