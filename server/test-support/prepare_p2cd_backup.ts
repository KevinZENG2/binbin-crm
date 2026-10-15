// Provision a disposable read-only backup login after commercial acceptance.
import {SQL} from 'bun';
const target=new URL(Bun.env.DATABASE_URL!),prefix=Bun.env.CRM_OBJECT_PREFIX!,password=Bun.env.CRM_ISOLATED_BACKUP_PASSWORD!;
if(Bun.env.CRM_P2CD_WRITE_SCOPE!=='isolated-new-database'||target.pathname!=='/crm_acceptance'||target.hostname!==Bun.env.CRM_ISOLATED_DATABASE_HOST||!target.hostname.endsWith('.railway.internal')||!/^p2cd-acceptance-\d+$/.test(prefix)||!/^[a-f0-9]{48}$/.test(password))throw Error('Private P2-C/D backup scope required');
const db=new SQL(Bun.env.DATABASE_URL!);try{
 const state=(await db`SELECT (SELECT count(*)::int FROM flyway_schema_history WHERE success) AS migrations,(SELECT count(*)::int FROM pg_roles WHERE rolname='crm_backup') AS existing`)[0];if(state.migrations!==9||state.existing!==0)throw Error('Fresh V9 backup fixture required');
 const files=await db`SELECT x.size_bytes,x.sha256,x.object_key FROM crm_attachment x JOIN crm_receipt r ON r.tenant_id=x.tenant_id AND r.proof_attachment_id=x.id WHERE x.status='READY' AND r.status IN('CONFIRMED','REVERSED')`;
 if(!files.length||files.some((f:any)=>!f.object_key.startsWith(prefix+'/attachments/')||f.size_bytes<=0))throw Error('Accepted receipt proof required');
 await db.begin(async tx=>{await tx.unsafe("CREATE ROLE crm_backup LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT BYPASSRLS PASSWORD '"+password+"'");await tx.unsafe('GRANT CONNECT ON DATABASE crm_acceptance TO crm_backup;GRANT USAGE ON SCHEMA public TO crm_backup;GRANT SELECT ON ALL TABLES IN SCHEMA public TO crm_backup;GRANT INSERT,UPDATE ON sys_job_run TO crm_backup;ALTER DEFAULT PRIVILEGES FOR ROLE crm_migrator IN SCHEMA public GRANT SELECT ON TABLES TO crm_backup');});
 const commercial=(await db`SELECT (SELECT count(*)::int FROM crm_contract) AS contracts,(SELECT count(*)::int FROM crm_contract_revision) AS contract_versions,(SELECT count(*)::int FROM crm_receipt) AS receipts,(SELECT count(*)::int FROM crm_receipt_reversal) AS reversals,(SELECT count(*)::int FROM crm_receipt_revision) AS receipt_versions`)[0];
 if(Object.values(commercial).some(v=>Number(v)<1))throw Error('Nonempty contract and receipt restore coverage required');
 console.log('CRM_P2CD_BACKUP_PREPARED '+JSON.stringify({privateDatabase:'crm_acceptance',migrations:9,files:files.length,attachmentBytes:files.reduce((n:number,f:any)=>n+Number(f.size_bytes),0),commercial,roleSuperuser:false}));
}finally{await db.close();}
