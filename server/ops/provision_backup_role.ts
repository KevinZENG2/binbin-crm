// One-off infrastructure setup. The role can read every tenant for encrypted backups,
// but cannot change business records, create schemas or authenticate to the API.
import {SQL} from 'bun';
const target=new URL(Bun.env.DATABASE_URL!);
if(!target.hostname.endsWith('.railway.internal')||target.pathname!=='/railway'||Bun.env.CRM_BACKUP_SETUP_SCOPE!=='crm-test-readonly-backup-role')throw new Error('Explicit private preview target required');
const password=Bun.env.CRM_BACKUP_PASSWORD!;
if(!/^[a-f0-9]{48}$/.test(password))throw new Error('Generated backup credential required');
target.searchParams.set('sslmode','require');
const db=new SQL(target.toString());
try{
 const result=await db.begin(async tx=>{
  const old=await tx`SELECT rolname FROM pg_roles WHERE rolname='crm_backup'`;
  if(old.length)throw new Error('Backup role already exists; preserve its credential and review separately');
  await tx.unsafe("CREATE ROLE crm_backup LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT BYPASSRLS PASSWORD '"+password+"'");
  for(const query of ['GRANT CONNECT ON DATABASE railway TO crm_backup','GRANT USAGE ON SCHEMA public TO crm_backup','GRANT SELECT ON ALL TABLES IN SCHEMA public TO crm_backup','ALTER DEFAULT PRIVILEGES FOR ROLE crm_migrator IN SCHEMA public GRANT SELECT ON TABLES TO crm_backup'])await tx.unsafe(query);
  await tx.unsafe('GRANT INSERT,UPDATE ON sys_job_run TO crm_backup');
  const role=(await tx`SELECT rolname,rolsuper,rolbypassrls,rolcreatedb,rolcreaterole,rolinherit,has_schema_privilege('crm_backup','public','CREATE') AS schema_create,has_table_privilege('crm_backup','sys_job_run','INSERT') AS job_insert,has_table_privilege('crm_backup','sys_job_run','UPDATE') AS job_update,has_table_privilege('crm_backup','sys_job_run','DELETE') AS job_delete FROM pg_roles WHERE rolname='crm_backup'`)[0];
  const tables=await tx`SELECT tablename,has_table_privilege('crm_backup',quote_ident(schemaname)||'.'||quote_ident(tablename),'SELECT') AS can_read,has_table_privilege('crm_backup',quote_ident(schemaname)||'.'||quote_ident(tablename),'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') AS can_write FROM pg_tables WHERE schemaname='public' ORDER BY tablename`;
  if(role.rolsuper||role.rolcreatedb||role.rolcreaterole||role.rolinherit||role.schema_create||role.job_delete||!role.rolbypassrls||!role.job_insert||!role.job_update||tables.some((t:any)=>!t.can_read||(t.tablename!=='sys_job_run'&&t.can_write)))throw new Error('Backup privilege check failed');
  const tls=(await tx`SELECT ssl FROM pg_stat_ssl WHERE pid=pg_backend_pid()`)[0];
  if(!tls?.ssl)throw new Error('Encrypted setup connection required');
  return{role,tables,tls:true,scope:'authorized dedicated preview backup role; no business mutations'};
 });
 console.log('CRM_BACKUP_ROLE '+JSON.stringify(result));
}catch{throw new Error('BACKUP_ROLE_SETUP_FAILED');}finally{await db.close();}
