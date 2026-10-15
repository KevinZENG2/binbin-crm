// Remove only this disposable run's S3 namespace after a verified restoration.
import {SQL} from 'bun';
const target=new URL(Bun.env.DATABASE_URL!),prefix=Bun.env.CRM_OBJECT_PREFIX!;
if(Bun.env.CRM_P2CD_WRITE_SCOPE!=='isolated-new-database'||target.pathname!=='/crm_acceptance'||target.hostname!==Bun.env.CRM_ISOLATED_DATABASE_HOST||!/^p2cd-acceptance-\d+$/.test(prefix))throw Error('Private isolated cleanup required');
const db=new SQL(Bun.env.DATABASE_URL!);try{
 const endpoint=new URL(Bun.env.CRM_S3_ENDPOINT!);endpoint.hostname=Bun.env.CRM_S3_BUCKET!+'.'+endpoint.hostname;const bucket=new Bun.S3Client({endpoint:endpoint.toString(),region:Bun.env.CRM_S3_REGION!,accessKeyId:Bun.env.CRM_S3_ACCESS_KEY!,secretAccessKey:Bun.env.CRM_S3_SECRET_KEY!,virtualHostedStyle:true});
 const files=await db`SELECT id,object_key FROM crm_attachment WHERE status='READY'`,backups=await db`SELECT id,artifact_key FROM sys_job_run WHERE job_name='DATABASE_BACKUP' AND status='SUCCESS' AND artifact_key IS NOT NULL`;
 if((backups.length===0&&files.length>0)||files.some((f:any)=>!f.object_key.startsWith(prefix+'/attachments/'))||backups.some((b:any)=>!/^backups\/acceptance-p2cd-\d+\//.test(b.artifact_key)||!b.artifact_key.endsWith('-'+b.id+'.snapshot.aesgcm')))throw Error('Verified disposable backup and namespace required');
 for(const f of files){await bucket.delete(f.object_key);if(await bucket.file(f.object_key).exists())throw Error('Disposable proof removal unconfirmed');await db`UPDATE crm_attachment SET status='DELETED',deleted_at=now() WHERE id=${f.id}::uuid`;}
 for(const b of backups){await bucket.delete(b.artifact_key);if(await bucket.file(b.artifact_key).exists())throw Error('Disposable snapshot removal unconfirmed');await db`UPDATE sys_job_run SET artifact_key=NULL WHERE id=${b.id}::uuid`;}
 const tables=await db`SELECT relname,relforcerowsecurity,pg_get_userbyid(relowner) AS owner FROM pg_class WHERE relnamespace='public'::regnamespace AND relrowsecurity`,migrations=await db`SELECT version,success FROM flyway_schema_history ORDER BY installed_rank`;
 if(tables.length!==34||tables.some((t:any)=>!t.relforcerowsecurity||t.owner!=='crm_migrator')||migrations.length!==9||migrations.some((m:any)=>!m.success))throw Error('Final P2-C/D schema checkpoint');
 console.log('CRM_P2CD_CLEANUP '+JSON.stringify({filesRemoved:files.length,snapshotsRemoved:backups.length,rlsTables:34,migrations:9,sourcePreviewTouched:false,finalCheckpointPassed:true}));
}finally{await db.close();}
