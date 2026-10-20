"""Encrypted, consistent PostgreSQL backup with an optional disposable restore rehearsal.
Requires PostgreSQL 18 client/server binaries, boto3, psycopg2 and cryptography.
Credentials are environment-only. No secrets, tokens or business bodies enter logs.
"""
import hashlib, json, os, pathlib, re, shutil, subprocess, tempfile, uuid, zipfile
from datetime import datetime, timezone, timedelta
from urllib.parse import urlparse, unquote
import boto3, psycopg2
from botocore.config import Config
from botocore.exceptions import ClientError
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

MAGIC=b'BBCRM01'; BLOCK=65536
BASE_TABLES=set('auth_login_limit auth_password_reset auth_reset_limit auth_session crm_activity crm_attachment crm_contact crm_customer crm_customer_share crm_import_batch crm_lead crm_opportunity crm_opportunity_history crm_task flyway_schema_history iam_audit iam_user org_department sys_audit_log sys_control_request sys_idempotency_request sys_job_run sys_member_role sys_notification sys_object_cleanup sys_plan sys_platform_audit sys_role_policy sys_subscription_request sys_support_access_log sys_support_grant sys_tenant sys_tenant_member'.split())
BASE_RLS=set('crm_customer crm_customer_share sys_idempotency_request sys_audit_log org_department sys_role_policy sys_support_access_log crm_contact crm_lead crm_opportunity crm_opportunity_history crm_activity crm_task crm_import_batch crm_attachment sys_notification sys_object_cleanup'.split())
CONTRACT_TABLES=set('crm_contract_counter crm_contract crm_contract_quote_link crm_collection_plan crm_contract_revision crm_contract_change'.split())
RECEIPT_TABLES=set('crm_receipt_counter crm_receipt crm_receipt_reversal crm_receipt_revision'.split())
APPROVAL_TABLES=set('crm_quote_approval crm_quote_approval_event'.split())
COMMERCE_TABLES=set('crm_product crm_quote_counter crm_quote crm_quote_line crm_quote_revision'.split())
SERVICE_TABLES=set('crm_ticket_counter crm_sla_policy crm_ticket crm_ticket_sla_episode crm_ticket_comment crm_ticket_revision crm_sla_breach crm_service_notification'.split())
KNOWLEDGE_TABLES=set('crm_knowledge_category crm_knowledge_article crm_knowledge_revision crm_ticket_article'.split())
CAMPAIGN_TABLES=set('crm_campaign crm_campaign_member crm_campaign_revision crm_campaign_execution crm_campaign_result crm_marketing_consent'.split())
INTEGRATION_TABLES=set('crm_integration_key crm_integration_nonce crm_webhook_subscription crm_webhook_outbox crm_webhook_attempt'.split())
CONFIG_TABLES=set('sys_industry_template sys_tenant_profile crm_custom_field crm_custom_value crm_tag crm_customer_tag crm_process_stage crm_number_rule crm_approval_rule crm_dictionary_item'.split())
CONFIG_RLS=CONFIG_TABLES-{'sys_industry_template'}
PORTAL_TABLES=set('portal_account portal_session portal_login_limit portal_audit portal_idempotency'.split())
AI_TABLES=set('crm_ai_run crm_ai_feedback'.split())
COMMERCIAL_OPERATIONS_TABLES=set('sys_subscription_order sys_usage_monthly'.split())
SALES_FOUNDATION_TABLES=set('crm_sales_resource_policy crm_resource_alert'.split())
RESOURCE_POOL_TABLES=set('crm_resource_movement'.split())
CUSTOMER_COLLABORATION_TABLES=set('crm_customer_lifecycle_history crm_followup'.split())
OPPORTUNITY_LINE_TABLES=set('crm_opportunity_line'.split())
SALES_ORDER_TABLES=set('crm_sales_order_counter crm_sales_order crm_sales_order_line crm_sales_order_event'.split())

P6_FINANCE_TABLES=set('crm_fund_account crm_fund_entry crm_invoice crm_invoice_red crm_expense crm_finance_event'.split())
P6_SERVICE_TABLES={'crm_service_feedback'}
P6_PERFORMANCE_TABLES=set('crm_sales_target crm_commission_rule crm_commission_run crm_commission_adjustment crm_sales_weekly_report crm_performance_event'.split())

P7_AUTOMATION_TABLES=set("crm_automation_rule crm_automation_revision crm_automation_event crm_automation_run crm_automation_attempt".split())
P7_WORKSPACE_TABLES=set("crm_object_policy crm_workspace_attachment crm_workspace_notice crm_public_form crm_public_submission".split())
P7_MIGRATION_TABLES=set("crm_migration_batch crm_pilot_run".split())

def schema_sets(version):
    tables=set(BASE_TABLES);rls=set(BASE_RLS)
    for required,group in ((6,COMMERCE_TABLES),(7,APPROVAL_TABLES),(8,CONTRACT_TABLES),(9,RECEIPT_TABLES),(10,SERVICE_TABLES),(11,KNOWLEDGE_TABLES),(12,CAMPAIGN_TABLES),(13,INTEGRATION_TABLES)):
        if version>=required:tables|=group;rls|=group
    if version>=14:tables|=CONFIG_TABLES;rls|=CONFIG_RLS
    if version>=15:tables|=PORTAL_TABLES
    if version>=16:tables|=AI_TABLES;rls|=AI_TABLES
    if version>=17:tables|=COMMERCIAL_OPERATIONS_TABLES
    for required,group in ((18,SALES_FOUNDATION_TABLES),(19,RESOURCE_POOL_TABLES),(20,CUSTOMER_COLLABORATION_TABLES),(21,OPPORTUNITY_LINE_TABLES),(22,SALES_ORDER_TABLES),(23,P6_FINANCE_TABLES),(24,P6_SERVICE_TABLES),(25,P6_PERFORMANCE_TABLES),(26,P7_AUTOMATION_TABLES),(27,P7_WORKSPACE_TABLES),(28,P7_MIGRATION_TABLES)):
        if version>=required:tables|=group;rls|=group
    return tables,rls

def validate_schema(info):
    versions=[str(row[0]) for row in info['migrations']]
    if versions not in [[str(i) for i in range(1,v+1)] for v in range(5,29)] or not all(row[1] for row in info['migrations']):raise RuntimeError('UNKNOWN_OR_FAILED_MIGRATION')
    version=int(versions[-1]);tables,rls=schema_sets(version)
    if set(info['counts'])!=tables:raise RuntimeError('UNEXPECTED_BACKUP_TABLE_SET')
    if {row[0] for row in info['rls']}!=rls or not all(row[1] and row[2] and row[3]=='crm_migrator' for row in info['rls']):raise RuntimeError('RESTORE_RLS_MISMATCH')
    return version

def backup_identity(row,prefix="backups/preview"):
    """Deletion accepts only an audited backup object belonging to this job UUID."""
    job,finished,key,sha,size=row
    match=re.fullmatch(re.escape(prefix)+r'/(\d{4}/\d{2}/\d{2})/(\d{6})-([0-9a-f-]{36})\.snapshot\.aesgcm',key or '')
    if not match or str(uuid.UUID(match[3]))!=str(job) or not re.fullmatch('[0-9a-f]{64}',sha or '') or not isinstance(size,int) or size<=len(MAGIC)+28:
        raise RuntimeError('RETENTION_OBJECT_IDENTITY_INVALID')
    stamp=datetime.strptime(match[1]+'/'+match[2],'%Y/%m/%d/%H%M%S').replace(tzinfo=timezone.utc)
    if finished.tzinfo is None or stamp>finished:raise RuntimeError('RETENTION_OBJECT_TIME_INVALID')
    return row

def object_matches(s3,bucket,row,allow_missing=False):
    try:head=s3.head_object(Bucket=bucket,Key=row[2])
    except ClientError as error:
        if allow_missing and error.response.get('Error',{}).get('Code') in ('404','NoSuchKey','NotFound'):return False
        raise RuntimeError('RETENTION_OBJECT_UNAVAILABLE') from None
    if head['ContentLength']!=row[4] or head.get('Metadata',{}).get('sha256')!=row[3] or head.get('Metadata',{}).get('encryption')!='AES-256-GCM':
        raise RuntimeError('RETENTION_OBJECT_METADATA_MISMATCH')
    return True

def retain_backups(db,s3,bucket,current_job,restore_verified,days=30,minimum=3,now=None,prefix="backups/preview"):
    # Keep all recent copies AND at least the newest successful copies. A failed
    # backup/rehearsal must never trigger cleanup. The caller holds the job lock.
    if not restore_verified or not 30<=days<=365 or not 3<=minimum<=30:raise RuntimeError('RETENTION_POLICY_INVALID')
    now=now or datetime.now(timezone.utc);cutoff=now-timedelta(days=days)
    retention_job=str(uuid.uuid4());deleted=0;already_missing=0
    with db.cursor() as cur:cur.execute("INSERT INTO sys_job_run(id,job_name,status) VALUES(%s,'BACKUP_RETENTION','RUNNING')",(retention_job,))
    try:
        with db.cursor() as cur:
            cur.execute("SELECT id,finished_at,artifact_key,artifact_sha256,artifact_bytes FROM sys_job_run WHERE job_name='DATABASE_BACKUP' AND status='SUCCESS' AND artifact_key IS NOT NULL ORDER BY finished_at DESC,id DESC")
            rows=[backup_identity(row,prefix) for row in cur.fetchall()]
        if not rows or str(rows[0][0])!=str(current_job):raise RuntimeError('RETENTION_FRESH_BACKUP_REQUIRED')
        protected=rows[:minimum]
        for row in protected:object_matches(s3,bucket,row)
        candidates=[row for row in rows[minimum:] if row[1]<cutoff]
        # Validate every present candidate before removing any object. An interrupted
        # deletion is retryable: clear an expired missing object's pointer next time.
        present={str(row[0]):object_matches(s3,bucket,row,allow_missing=True) for row in candidates}
        for row in candidates:
            if present[str(row[0])]:
                s3.delete_object(Bucket=bucket,Key=row[2])
                if object_matches(s3,bucket,row,allow_missing=True):raise RuntimeError('RETENTION_DELETE_NOT_CONFIRMED')
                deleted+=1
            else:already_missing+=1
            with db.cursor() as cur:
                cur.execute("UPDATE sys_job_run SET artifact_key=NULL WHERE id=%s AND job_name='DATABASE_BACKUP' AND status='SUCCESS' AND artifact_key=%s AND artifact_sha256=%s",(row[0],row[2],row[3]))
                if cur.rowcount!=1:raise RuntimeError('RETENTION_AUDIT_CONFLICT')
        with db.cursor() as cur:cur.execute("UPDATE sys_job_run SET status='SUCCESS',finished_at=now(),processed_count=%s WHERE id=%s",(deleted+already_missing,retention_job))
    except Exception:
        with db.cursor() as cur:cur.execute("UPDATE sys_job_run SET status='FAILED',finished_at=now(),processed_count=%s,error_code='BACKUP_RETENTION_FAILED' WHERE id=%s",(deleted+already_missing,retention_job))
        raise RuntimeError('BACKUP_RETENTION_FAILED') from None
    return {'jobId':retention_job,'days':days,'minimumSuccessful':minimum,'availableCopies':len(rows)-len(candidates),'expiredDeleted':deleted,'expiredAlreadyMissing':already_missing,'auditRecordsRetained':True}

def digest(path):
    sha=hashlib.sha256()
    with open(path,'rb') as stream:
        for data in iter(lambda:stream.read(BLOCK),b''):sha.update(data)
    return sha.hexdigest()

def encrypt(source,target,key):
    nonce=os.urandom(12); cipher=Cipher(algorithms.AES(key),modes.GCM(nonce)).encryptor();cipher.authenticate_additional_data(MAGIC)
    with open(source,'rb') as src,open(target,'wb') as dst:
        dst.write(MAGIC+nonce)
        for data in iter(lambda:src.read(BLOCK),b''):dst.write(cipher.update(data))
        dst.write(cipher.finalize());dst.write(cipher.tag)

def decrypt(source,target,key):
    length=os.path.getsize(source)
    with open(source,'rb') as src,open(target,'wb') as dst:
        if src.read(len(MAGIC))!=MAGIC or length<len(MAGIC)+28:raise ValueError('Invalid encrypted backup')
        nonce=src.read(12);src.seek(-16,2);tag=src.read(16);src.seek(len(MAGIC)+12)
        cipher=Cipher(algorithms.AES(key),modes.GCM(nonce,tag)).decryptor();cipher.authenticate_additional_data(MAGIC)
        remaining=length-len(MAGIC)-28
        while remaining:
            data=src.read(min(BLOCK,remaining));remaining-=len(data);dst.write(cipher.update(data))
        dst.write(cipher.finalize())

def command(args,env=None):
    # Capture command output; errors are reported as a fixed code, never with DB URLs.
    result=subprocess.run(args,env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    if result.returncode:raise RuntimeError('BACKUP_COMMAND_FAILED')
    return result.stdout.decode().strip()

def table_counts(conn):
    with conn.cursor() as cur:
        cur.execute("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename")
        tables=[r[0] for r in cur.fetchall()];counts={}
        for table in tables:
            cur.execute('SELECT count(*) FROM public."'+table.replace('"','""')+'"');counts[table]=cur.fetchone()[0]
        cur.execute("SELECT version,success FROM flyway_schema_history ORDER BY installed_rank");migrations=cur.fetchall()
        cur.execute("SELECT relname,relrowsecurity,relforcerowsecurity,pg_get_userbyid(relowner) FROM pg_class WHERE relnamespace='public'::regnamespace AND relrowsecurity ORDER BY relname");rls=cur.fetchall()
    commercial={}
    verified_tables=COMMERCE_TABLES|APPROVAL_TABLES|CONTRACT_TABLES|RECEIPT_TABLES|SERVICE_TABLES|KNOWLEDGE_TABLES|CAMPAIGN_TABLES|INTEGRATION_TABLES|CONFIG_TABLES|PORTAL_TABLES|AI_TABLES|COMMERCIAL_OPERATIONS_TABLES|SALES_FOUNDATION_TABLES|RESOURCE_POOL_TABLES|CUSTOMER_COLLABORATION_TABLES|OPPORTUNITY_LINE_TABLES|SALES_ORDER_TABLES|P6_FINANCE_TABLES|P6_SERVICE_TABLES|P6_PERFORMANCE_TABLES|P7_AUTOMATION_TABLES|P7_WORKSPACE_TABLES|P7_MIGRATION_TABLES
    for name in sorted(verified_tables&set(tables)):
        sha=hashlib.sha256()
        with conn.cursor() as cur:
            cur.execute('SELECT to_jsonb(t)::text FROM public."'+name+'" t ORDER BY to_jsonb(t)::text')
            while True:
                batch=cur.fetchmany(500)
                if not batch:break
                for row in batch:sha.update(row[0].encode());sha.update(b'\n')
        commercial[name]=sha.hexdigest()
    return {'counts':counts,'migrations':migrations,'rls':rls,'commercialDigests':commercial}

def rehearse(dump,expected,root):
    if os.getenv('CRM_BACKUP_REHEARSAL')!='true':return None
    pgdata=pathlib.Path(root)/'restore-pg';pgdata.mkdir();shutil.chown(pgdata,user='postgres',group='postgres')
    command(['runuser','-u','postgres','--','initdb','-D',str(pgdata),'-A','trust','--no-locale','--encoding=UTF8'])
    command(['runuser','-u','postgres','--','pg_ctl','-D',str(pgdata),'-l',str(pgdata/'log'),'-o','-p 5544 -h 127.0.0.1 -k /tmp','-w','start'])
    restore=None
    try:
        restore=psycopg2.connect(host='127.0.0.1',port=5544,user='postgres',dbname='postgres');restore.autocommit=True
        with restore.cursor() as cur:
            cur.execute("SELECT count(*) FROM pg_database WHERE NOT datistemplate AND datname<>'postgres'")
            if cur.fetchone()[0]!=0:raise RuntimeError('RESTORE_TARGET_NOT_EMPTY')
            cur.execute('CREATE ROLE crm_runtime NOLOGIN NOBYPASSRLS; CREATE ROLE crm_migrator NOLOGIN NOBYPASSRLS; CREATE ROLE crm_backup NOLOGIN BYPASSRLS')
            cur.execute('CREATE DATABASE crm_restore_validation')
        command(['pg_restore','--exit-on-error','--host=127.0.0.1','--port=5544','--username=postgres','--dbname=crm_restore_validation',str(dump)],env={**os.environ,'PGPASSWORD':''})
        restored=psycopg2.connect(host='127.0.0.1',port=5544,user='postgres',dbname='crm_restore_validation');actual=table_counts(restored);restored.close()
        if actual!=expected:raise RuntimeError('RESTORE_SCHEMA_OR_COUNT_MISMATCH')
        validate_schema(actual)
        # Verify a runtime role with no tenant context cannot see restored business rows.
        probe=psycopg2.connect(host='127.0.0.1',port=5544,user='postgres',dbname='crm_restore_validation')
        with probe.cursor() as cur:
            cur.execute('SET ROLE crm_runtime');cur.execute('SELECT count(*) FROM crm_customer');hidden=cur.fetchone()[0]==0
            if actual['commercialDigests']:
                cur.execute('SELECT (SELECT count(*) FROM crm_product)+(SELECT count(*) FROM crm_quote)+(SELECT count(*) FROM crm_quote_revision)');hidden=hidden and cur.fetchone()[0]==0
            if 'crm_quote_approval' in actual['counts']:
                cur.execute('SELECT (SELECT count(*) FROM crm_quote_approval)+(SELECT count(*) FROM crm_quote_approval_event)');hidden=hidden and cur.fetchone()[0]==0
        if 'crm_contract' in actual['counts']:
            with probe.cursor() as cur:cur.execute('SELECT (SELECT count(*) FROM crm_contract)+(SELECT count(*) FROM crm_collection_plan)');hidden=hidden and cur.fetchone()[0]==0
        if 'crm_receipt' in actual['counts']:
            with probe.cursor() as cur:cur.execute('SELECT (SELECT count(*) FROM crm_receipt)+(SELECT count(*) FROM crm_receipt_reversal)');hidden=hidden and cur.fetchone()[0]==0
        probe.close()
        if not hidden:raise RuntimeError('RESTORE_RUNTIME_BOUNDARY_FAILED')
        return {'target':'new disposable PostgreSQL 18 database','tableCount':len(actual['counts']),'rowCount':sum(actual['counts'].values()),'rlsTables':len(actual['rls']),'migrations':len(actual['migrations']),'runtimeTenantBoundary':True,'commercialContentVerified':bool(actual['commercialDigests']),'commercialTablesVerified':len(actual['commercialDigests']),'removedAfterCheck':True}
    finally:
        if restore:restore.close()
        command(['runuser','-u','postgres','--','pg_ctl','-D',str(pgdata),'-m','fast','-w','stop'])

def main():
    target=urlparse(os.environ['CRM_BACKUP_DATABASE_URL']);prefix=os.environ.get('CRM_BACKUP_PREFIX','backups/preview')
    isolated=os.getenv('CRM_BACKUP_ISOLATED_ACCEPTANCE')=='true'
    valid_isolated=isolated and target.path=='/crm_acceptance' and target.hostname==os.getenv('CRM_ISOLATED_DATABASE_HOST') and re.fullmatch(r'backups/acceptance-[a-z0-9-]{8,80}',prefix)
    valid_preview=not isolated and target.path=='/railway' and prefix=='backups/preview'
    if not target.hostname.endswith('.railway.internal') or not (valid_preview or valid_isolated):raise RuntimeError('PRIVATE_PREVIEW_OR_EXPLICIT_ISOLATED_BACKUP_REQUIRED')
    object_prefix=os.environ.get('CRM_OBJECT_PREFIX','preview') if isolated else 'preview'
    if isolated and not re.fullmatch(r'p(?:2(?:ab|cd)|3|4(?:ab|full))-acceptance-\d+',object_prefix):raise RuntimeError('ISOLATED_ATTACHMENT_NAMESPACE_REQUIRED')
    key=bytes.fromhex(os.environ['CRM_BACKUP_ENCRYPTION_KEY'])
    if len(key)!=32:raise RuntimeError('BACKUP_KEY_REQUIRED')
    days=int(os.getenv('CRM_BACKUP_RETENTION_DAYS','30'));minimum=int(os.getenv('CRM_BACKUP_MIN_SUCCESSFUL','3'))
    if os.getenv('CRM_BACKUP_REHEARSAL')!='true' or not 30<=days<=365 or not 3<=minimum<=30:raise RuntimeError('VERIFIED_BACKUP_RETENTION_POLICY_REQUIRED')
    db=psycopg2.connect(os.environ['CRM_BACKUP_DATABASE_URL']);db.autocommit=True;job=str(uuid.uuid4());locked=False;stage='CONNECT'
    try:
        with db.cursor() as cur:
            cur.execute('SELECT pg_try_advisory_lock(64012026)');locked=cur.fetchone()[0]
            if not locked:print('CRM_BACKUP_BUSY');return
            cur.execute('INSERT INTO sys_job_run(id,job_name,status) VALUES(%s,\'DATABASE_BACKUP\',\'RUNNING\')',(job,))
        s3=boto3.client('s3',endpoint_url=os.environ['CRM_S3_ENDPOINT'],region_name=os.environ.get('CRM_S3_REGION','auto'),aws_access_key_id=os.environ['CRM_S3_ACCESS_KEY'],aws_secret_access_key=os.environ['CRM_S3_SECRET_KEY'],config=Config(s3={'addressing_style':'virtual'},connect_timeout=10,read_timeout=60,retries={'max_attempts':3}))
        with tempfile.TemporaryDirectory(prefix='crm-backup-') as temporary:
            root=pathlib.Path(temporary);os.chmod(root,0o755);dump=root/'database.dump';package=root/'snapshot.zip';encrypted=root/'database.enc';retrieved=root/'retrieved.enc';restored=root/'restored.zip'
            snapshot=psycopg2.connect(os.environ['CRM_BACKUP_DATABASE_URL']);snapshot.set_session(isolation_level='REPEATABLE READ',readonly=True,autocommit=False)
            try:
                with snapshot.cursor() as cur:
                    cur.execute('SELECT pg_export_snapshot()');snapshot_id=cur.fetchone()[0];cur.execute('SHOW server_version_num');version=int(cur.fetchone()[0])
                if version//10000!=18:raise RuntimeError('EXPECTED_POSTGRESQL_18')
                stage='SNAPSHOT_SCHEMA';expected=table_counts(snapshot)
                schema_version=validate_schema(expected)
                with snapshot.cursor() as cur:
                    cur.execute("SELECT id::text AS id,object_key,sha256,size_bytes FROM crm_attachment WHERE status='READY'"+(" UNION ALL SELECT 'workspace-'||id::text,object_key,sha256,size_bytes FROM crm_workspace_attachment WHERE status='READY'" if schema_version>=27 else "")+" ORDER BY id")
                    attachments=[{'id':str(r[0]),'objectKey':r[1],'sha256':r[2],'bytes':r[3]} for r in cur.fetchall()]
                pg_env={**os.environ,'PGHOST':target.hostname,'PGPORT':str(target.port or 5432),'PGUSER':unquote(target.username),'PGPASSWORD':unquote(target.password),'PGDATABASE':target.path[1:],'PGSSLMODE':'require'}
                stage='DUMP';command(['pg_dump','--format=custom','--snapshot='+snapshot_id,'--file='+str(dump)],pg_env)
            finally:snapshot.rollback();snapshot.close()
            # Bundle all files referenced by the same database snapshot. A missing or
            # altered object fails the whole backup instead of claiming success.
            stage='ATTACHMENTS'
            with zipfile.ZipFile(package,'w',compression=zipfile.ZIP_DEFLATED) as archive:
                archive.write(dump,'database.dump')
                archive.writestr('manifest.json',json.dumps({'schema':schema_version,'databaseSha256':digest(dump),'attachments':attachments}))
                for attachment in attachments:
                    if not attachment['objectKey'].startswith(object_prefix+'/attachments/'):raise RuntimeError('UNEXPECTED_ATTACHMENT_NAMESPACE')
                    data=s3.get_object(Bucket=os.environ['CRM_S3_BUCKET'],Key=attachment['objectKey'])['Body'].read()
                    if len(data)!=attachment['bytes'] or hashlib.sha256(data).hexdigest()!=attachment['sha256']:raise RuntimeError('BACKUP_ATTACHMENT_INTEGRITY_FAILED')
                    archive.writestr('files/'+attachment['id'],data)
            stage='ENCRYPT_AND_READBACK';plain_hash=digest(package);encrypt(package,encrypted,key);sha=digest(encrypted);stamp=datetime.now(timezone.utc).strftime('%Y/%m/%d/%H%M%S');object_key=f'{prefix}/{stamp}-{job}.snapshot.aesgcm'
            s3.upload_file(str(encrypted),os.environ['CRM_S3_BUCKET'],object_key,ExtraArgs={'ContentType':'application/octet-stream','Metadata':{'sha256':sha,'encryption':'AES-256-GCM','schema':str(schema_version)}})
            s3.download_file(os.environ['CRM_S3_BUCKET'],object_key,str(retrieved))
            if digest(retrieved)!=sha:raise RuntimeError('BACKUP_OBJECT_HASH_MISMATCH')
            decrypt(retrieved,restored,key)
            if digest(restored)!=plain_hash:raise RuntimeError('BACKUP_DECRYPT_HASH_MISMATCH')
            # A tampered copy must fail authentication; never attempt its restore.
            damaged=root/'tampered.enc';shutil.copyfile(retrieved,damaged)
            with open(damaged,'r+b') as stream:stream.seek(-17,2);b=stream.read(1);stream.seek(-1,1);stream.write(bytes([b[0]^1]))
            try:decrypt(damaged,root/'tampered.dump',key)
            except Exception:tamper_rejected=True
            else:raise RuntimeError('BACKUP_TAMPER_NOT_REJECTED')
            with zipfile.ZipFile(restored) as archive:
                manifest=json.loads(archive.read('manifest.json'))
                if manifest['attachments']!=attachments:raise RuntimeError('RESTORE_ATTACHMENT_MANIFEST_MISMATCH')
                restored_dump=root/'restored.dump';restored_dump.write_bytes(archive.read('database.dump'))
                if digest(restored_dump)!=manifest['databaseSha256']:raise RuntimeError('RESTORE_DUMP_HASH_MISMATCH')
                for attachment in manifest['attachments']:
                    data=archive.read('files/'+attachment['id'])
                    if len(data)!=attachment['bytes'] or hashlib.sha256(data).hexdigest()!=attachment['sha256']:raise RuntimeError('RESTORE_ATTACHMENT_HASH_MISMATCH')
            stage='RESTORE';restore_evidence=rehearse(restored_dump,expected,root)
            if restore_evidence is not None:restore_evidence['attachmentFilesVerified']=len(attachments)
            size=encrypted.stat().st_size
            with db.cursor() as cur:cur.execute("UPDATE sys_job_run SET status='SUCCESS',finished_at=now(),processed_count=%s,artifact_key=%s,artifact_sha256=%s,artifact_bytes=%s WHERE id=%s",(len(expected['counts']),object_key,sha,size,job))
            # Retention failure does not relabel an already verified backup as
            # failed. Record a separate task and fail this process for monitoring.
            print('CRM_BACKUP_RESULT '+json.dumps({'jobId':job,'status':'SUCCESS','encryptedBytes':size,'encryptedSha256':sha,'s3Readback':True,'authenticatedDecryption':True,'tamperRejected':tamper_rejected,'attachmentFiles':len(attachments),'restore':restore_evidence},ensure_ascii=False),flush=True)
            try:retention=retain_backups(db,s3,os.environ['CRM_S3_BUCKET'],job,restore_evidence is not None,days,minimum,prefix=prefix)
            except Exception:
                print('CRM_BACKUP_RETENTION_FAILED',flush=True)
                raise RuntimeError('BACKUP_RETENTION_FAILED') from None
            print('CRM_BACKUP_RETENTION '+json.dumps(retention),flush=True)
    except Exception:
        if locked:
            with db.cursor() as cur:cur.execute("UPDATE sys_job_run SET status='FAILED',finished_at=now(),error_code='BACKUP_OR_RESTORE_FAILED' WHERE id=%s AND status='RUNNING'",(job,))
        print('CRM_BACKUP_FAILED_STAGE '+stage,flush=True);print('CRM_BACKUP_FAILED');raise RuntimeError('BACKUP_OR_RESTORE_FAILED') from None
    finally:
        if locked:
            with db.cursor() as cur:cur.execute('SELECT pg_advisory_unlock(64012026)')
        db.close()

if __name__=='__main__':main()
