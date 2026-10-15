// Remove only disposable acceptance objects after successful restore verification.
import {SQL} from 'bun';
const target=new URL(Bun.env.DATABASE_URL!),prefix=Bun.env.CRM_OBJECT_PREFIX!;
if(Bun.env.CRM_P2A_WRITE_SCOPE!=='isolated-new-database'||target.pathname!=='/crm_acceptance'||target.hostname!==Bun.env.CRM_ISOLATED_DATABASE_HOST||!/^p2a-acceptance-\d+$/.test(prefix))throw new Error('P2-A isolated cleanup guard');
const db=new SQL(Bun.env.DATABASE_URL!);try{
 const endpoint=new URL(Bun.env.CRM_S3_ENDPOINT!);endpoint.hostname=Bun.env.CRM_S3_BUCKET!+'.'+endpoint.hostname;const bucket=new Bun.S3Client({endpoint:endpoint.toString(),region:Bun.env.CRM_S3_REGION!,accessKeyId:Bun.env.CRM_S3_ACCESS_KEY!,secretAccessKey:Bun.env.CRM_S3_SECRET_KEY!,virtualHostedStyle:true});
 const files=await db`SELECT id,object_key FROM crm_attachment WHERE status='READY'`,backups=await db`SELECT id,artifact_key FROM sys_job_run WHERE job_name='DATABASE_BACKUP' AND status='SUCCESS' AND artifact_key IS NOT NULL`;
 if(files.some((f:any)=>!f.object_key.startsWith(prefix+'/attachments/'))||backups.some((b:any)=>!/^backups\/acceptance-p2a-\d+\//.test(b.artifact_key)||!b.artifact_key.endsWith('-'+b.id+'.snapshot.aesgcm')))throw new Error('Unexpected disposable object');
 for(const f of files){await bucket.delete(f.object_key);await db`UPDATE crm_attachment SET status='DELETED',deleted_at=now() WHERE id=${f.id}::uuid`;}
 for(const b of backups){await bucket.delete(b.artifact_key);await db`UPDATE sys_job_run SET artifact_key=NULL WHERE id=${b.id}::uuid`;}
 const tables=await db`SELECT relname,relrowsecurity,relforcerowsecurity,pg_get_userbyid(relowner) AS owner FROM pg_class WHERE relnamespace='public'::regnamespace AND relrowsecurity ORDER BY relname`,migrations=await db`SELECT version,success FROM flyway_schema_history ORDER BY installed_rank`,commerce=(await db`SELECT (SELECT count(*)::int FROM crm_product) AS products,(SELECT count(*)::int FROM crm_quote) AS quotes,(SELECT count(*)::int FROM crm_quote_line) AS lines,(SELECT count(*)::int FROM crm_quote_revision) AS revisions`)[0];
 if(tables.length!==22||tables.some((t:any)=>!t.relforcerowsecurity||t.owner!=='crm_migrator')||migrations.length!==6||migrations.some((m:any)=>!m.success))throw new Error('P2-A final checkpoint failed');
 console.log('CRM_P2A_CLEANUP '+JSON.stringify({privateDatabase:'crm_acceptance',filesRemoved:files.length,snapshotsRemoved:backups.length,rlsTables:tables.length,migrations:migrations.length,commerce,sourcePreviewTouched:false,finalCheckpointPassed:true}));
}finally{await db.close();}
