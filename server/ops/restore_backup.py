"""Manual recovery into an explicitly named EMPTY database and new file namespace.
Never overwrites the preview database or existing objects. Uses env-only credentials.
"""
import json, os, pathlib, tempfile, uuid, zipfile
from urllib.parse import urlparse,unquote
import boto3, psycopg2
from botocore.config import Config
from backup import decrypt,digest,command,table_counts,validate_schema

def main():
    target=urlparse(os.environ['CRM_RESTORE_DATABASE_URL']);name=target.path[1:];prefix=os.environ['CRM_RESTORE_OBJECT_PREFIX'];backup_key=os.environ['CRM_RESTORE_BACKUP_KEY']
    if not target.hostname.endswith('.railway.internal') or not name.startswith('crm_restore_') or name!=os.environ['CRM_RESTORE_CONFIRM_DATABASE'] or target.path=='/railway':raise RuntimeError('EXPLICIT_NEW_RESTORE_DATABASE_REQUIRED')
    if not prefix.startswith('restore-') or len(prefix)>80 or not all(c.islower() or c.isdigit() or c=='-' for c in prefix):raise RuntimeError('NEW_RESTORE_OBJECT_NAMESPACE_REQUIRED')
    if not backup_key.startswith('backups/preview/') or not backup_key.endswith('.snapshot.aesgcm'):raise RuntimeError('BACKUP_OBJECT_REQUIRED')
    db=psycopg2.connect(os.environ['CRM_RESTORE_DATABASE_URL']);db.autocommit=True
    uploaded=[];s3=None
    try:
        with db.cursor() as cur:
            cur.execute("SELECT count(*) FROM pg_tables WHERE schemaname='public'")
            if cur.fetchone()[0]!=0:raise RuntimeError('RESTORE_DATABASE_NOT_EMPTY')
            cur.execute("SELECT count(*) FROM pg_roles WHERE rolname IN ('crm_runtime','crm_migrator','crm_backup')")
            if cur.fetchone()[0]!=3:raise RuntimeError('PREPARE_RESTORE_ROLES_FIRST')
        s3=boto3.client('s3',endpoint_url=os.environ['CRM_S3_ENDPOINT'],region_name=os.environ.get('CRM_S3_REGION','auto'),aws_access_key_id=os.environ['CRM_S3_ACCESS_KEY'],aws_secret_access_key=os.environ['CRM_S3_SECRET_KEY'],config=Config(s3={'addressing_style':'virtual'}))
        with tempfile.TemporaryDirectory(prefix='crm-restore-') as temporary:
            root=pathlib.Path(temporary);encrypted=root/'snapshot.enc';snapshot=root/'snapshot.zip';dump=root/'database.dump'
            s3.download_file(os.environ['CRM_S3_BUCKET'],backup_key,str(encrypted))
            if digest(encrypted)!=os.environ['CRM_RESTORE_BACKUP_SHA256']:raise RuntimeError('RESTORE_BACKUP_HASH_MISMATCH')
            decrypt(encrypted,snapshot,bytes.fromhex(os.environ['CRM_BACKUP_ENCRYPTION_KEY']))
            with zipfile.ZipFile(snapshot) as archive:
                manifest=json.loads(archive.read('manifest.json'));dump.write_bytes(archive.read('database.dump'))
                if manifest.get('schema') not in tuple(range(5,16)):raise RuntimeError('KNOWN_BACKUP_SCHEMA_REQUIRED')
                if digest(dump)!=manifest['databaseSha256']:raise RuntimeError('RESTORE_DUMP_HASH_MISMATCH')
                for item in manifest['attachments']:
                    import hashlib
                    data=archive.read('files/'+str(uuid.UUID(item['id'])))
                    if len(data)!=item['bytes'] or hashlib.sha256(data).hexdigest()!=item['sha256']:raise RuntimeError('RESTORE_FILE_HASH_MISMATCH')
            pg_env={**os.environ,'PGHOST':target.hostname,'PGPORT':str(target.port or 5432),'PGUSER':unquote(target.username),'PGPASSWORD':unquote(target.password),'PGDATABASE':name,'PGSSLMODE':'require'}
            command(['pg_restore','--exit-on-error','--dbname='+name,str(dump)],pg_env)
            with zipfile.ZipFile(snapshot) as archive:
                for item in manifest['attachments']:
                    if not item['objectKey'].startswith('preview/attachments/'):raise RuntimeError('RESTORE_SOURCE_NAMESPACE_INVALID')
                    new_key=prefix+item['objectKey'][len('preview'):]
                    # Conditional writes protect any pre-existing objects.
                    s3.put_object(Bucket=os.environ['CRM_S3_BUCKET'],Key=new_key,Body=archive.read('files/'+item['id']),IfNoneMatch='*');uploaded.append(new_key)
                    with db.cursor() as cur:cur.execute('UPDATE crm_attachment SET object_key=%s WHERE id=%s AND object_key=%s',(new_key,item['id'],item['objectKey']))
            with db.cursor() as cur:
                # Pending deletions belonged to the source bucket namespace. They must
                # never delete source files when the restored application starts.
                cur.execute('DELETE FROM sys_object_cleanup')
                cur.execute('UPDATE crm_attachment SET object_key=%s || substring(object_key from 8) WHERE status=\'DELETED\' AND object_key LIKE \'preview/attachments/%\'',(prefix,))
                cur.execute('TRUNCATE auth_session,auth_password_reset,auth_reset_limit,auth_login_limit')
                cur.execute("UPDATE iam_user SET auth_version=auth_version+1")
            info=table_counts(db);version=validate_schema(info)
            if version!=manifest['schema']:raise RuntimeError('RESTORE_MANIFEST_SCHEMA_MISMATCH')
            print('CRM_MANUAL_RESTORE '+json.dumps({'targetDatabase':name,'sourceUnchanged':True,'attachmentsCopied':len(uploaded),'sessionsRevoked':True,'rlsTables':len(info['rls']),'schemaVersion':version}))
    except Exception:
        print('CRM_MANUAL_RESTORE_FAILED: target must be discarded or reviewed before use')
        raise RuntimeError('RESTORE_FAILED_NEW_TARGET_ONLY') from None
    finally:db.close()

if __name__=='__main__':main()
