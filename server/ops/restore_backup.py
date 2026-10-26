"""Manual recovery into an explicitly named EMPTY database and new file namespace.
Never overwrites the preview database or existing objects. Uses env-only credentials.
"""
import hashlib, json, os, pathlib, re, tempfile, uuid, zipfile
from urllib.parse import urlparse,unquote

def restore_target(environ):
    target=urlparse(environ['CRM_RESTORE_DATABASE_URL']);name=target.path[1:]
    prefix=environ['CRM_RESTORE_OBJECT_PREFIX'];backup_key=environ['CRM_RESTORE_BACKUP_KEY']
    if target.scheme not in ('postgres','postgresql') or not target.hostname or not target.hostname.endswith('.railway.internal') or not re.fullmatch(r'crm_restore_[a-z0-9_]+',name) or name!=environ['CRM_RESTORE_CONFIRM_DATABASE']:
        raise RuntimeError('EXPLICIT_NEW_RESTORE_DATABASE_REQUIRED')
    if not re.fullmatch(r'restore-[a-z0-9-]{1,72}',prefix):raise RuntimeError('NEW_RESTORE_OBJECT_NAMESPACE_REQUIRED')
    if not backup_key.startswith('backups/preview/') or not backup_key.endswith('.snapshot.aesgcm'):raise RuntimeError('BACKUP_OBJECT_REQUIRED')
    return target,name,prefix,backup_key

def attachment_identity(item,schema):
    stored=item['id'];workspace=isinstance(stored,str) and stored.startswith('workspace-')
    if workspace and schema<27:raise RuntimeError('RESTORE_WORKSPACE_SCHEMA_REQUIRED')
    raw=stored[len('workspace-'):] if workspace else stored
    try:identity=str(uuid.UUID(raw))
    except (TypeError,ValueError,AttributeError):raise RuntimeError('RESTORE_FILE_ID_INVALID') from None
    if identity!=raw:raise RuntimeError('RESTORE_FILE_ID_INVALID')
    if not isinstance(item['objectKey'],str) or not item['objectKey'].startswith('preview/attachments/') or '..' in item['objectKey'].split('/'):
        raise RuntimeError('RESTORE_SOURCE_NAMESPACE_INVALID')
    if not isinstance(item['sha256'],str) or not re.fullmatch('[a-f0-9]{64}',item['sha256']) or type(item['bytes']) is not int or item['bytes']<0:
        raise RuntimeError('RESTORE_FILE_METADATA_INVALID')
    return ('crm_workspace_attachment' if workspace else 'crm_attachment'),identity,stored

def validate_manifest(manifest):
    schema=manifest.get('schema')
    if type(schema) is not int or not 5<=schema<=30:raise RuntimeError('KNOWN_BACKUP_SCHEMA_REQUIRED')
    if not isinstance(manifest.get('databaseSha256'),str) or not re.fullmatch('[a-f0-9]{64}',manifest['databaseSha256']) or not isinstance(manifest.get('attachments'),list):
        raise RuntimeError('RESTORE_MANIFEST_INVALID')
    identities=[attachment_identity(item,schema) for item in manifest['attachments']]
    if len(identities)!=len(set(identities)):raise RuntimeError('RESTORE_DUPLICATE_FILE_ID')
    return identities

def main():
    target,name,prefix,backup_key=restore_target(os.environ)
    import boto3, psycopg2
    from botocore.config import Config
    from backup import decrypt,digest,command,table_counts,validate_schema
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
                manifest=json.loads(archive.read('manifest.json'));identities=validate_manifest(manifest)
                dump.write_bytes(archive.read('database.dump'))
                if digest(dump)!=manifest['databaseSha256']:raise RuntimeError('RESTORE_DUMP_HASH_MISMATCH')
                for item,(_,_,stored) in zip(manifest['attachments'],identities):
                    data=archive.read('files/'+stored)
                    if len(data)!=item['bytes'] or hashlib.sha256(data).hexdigest()!=item['sha256']:raise RuntimeError('RESTORE_FILE_HASH_MISMATCH')
            pg_env={**os.environ,'PGHOST':target.hostname,'PGPORT':str(target.port or 5432),'PGUSER':unquote(target.username),'PGPASSWORD':unquote(target.password),'PGDATABASE':name,'PGSSLMODE':'require'}
            command(['pg_restore','--exit-on-error','--dbname='+name,str(dump)],pg_env)
            with zipfile.ZipFile(snapshot) as archive:
                for item,(table,identity,stored) in zip(manifest['attachments'],identities):
                    new_key=prefix+item['objectKey'][len('preview'):]
                    # Conditional writes protect any pre-existing objects.
                    s3.put_object(Bucket=os.environ['CRM_S3_BUCKET'],Key=new_key,Body=archive.read('files/'+stored),IfNoneMatch='*');uploaded.append(new_key)
                    readback=s3.get_object(Bucket=os.environ['CRM_S3_BUCKET'],Key=new_key)['Body'].read()
                    if len(readback)!=item['bytes'] or hashlib.sha256(readback).hexdigest()!=item['sha256']:raise RuntimeError('RESTORE_FILE_READBACK_MISMATCH')
                    with db.cursor() as cur:
                        cur.execute('UPDATE '+table+' SET object_key=%s WHERE id=%s AND object_key=%s',(new_key,identity,item['objectKey']))
                        if cur.rowcount!=1:raise RuntimeError('RESTORE_FILE_RECORD_MISMATCH')
            with db.cursor() as cur:
                # Pending deletions belonged to the source bucket namespace. They must
                # never delete source files when the restored application starts.
                cur.execute('DELETE FROM sys_object_cleanup')
                cur.execute('UPDATE crm_attachment SET object_key=%s || substring(object_key from 8) WHERE status=\'DELETED\' AND object_key LIKE \'preview/attachments/%\'',(prefix,))
                if manifest['schema']>=27:
                    cur.execute('UPDATE crm_workspace_attachment SET object_key=%s || substring(object_key from 8) WHERE status=\'DELETED\' AND object_key LIKE \'preview/attachments/%\'',(prefix,))
                cur.execute('TRUNCATE auth_session,auth_password_reset,auth_reset_limit,auth_login_limit')
                if manifest['schema']>=15:cur.execute('TRUNCATE portal_session')
                cur.execute("UPDATE iam_user SET auth_version=auth_version+1")
            info=table_counts(db);version=validate_schema(info)
            if version!=manifest['schema']:raise RuntimeError('RESTORE_MANIFEST_SCHEMA_MISMATCH')
            print('CRM_MANUAL_RESTORE '+json.dumps({'targetDatabase':name,'sourceUnchanged':True,'attachmentsCopied':len(uploaded),'sessionsRevoked':True,'rlsTables':len(info['rls']),'schemaVersion':version}))
    except Exception:
        print('CRM_MANUAL_RESTORE_FAILED: target must be discarded or reviewed before use')
        raise RuntimeError('RESTORE_FAILED_NEW_TARGET_ONLY') from None
    finally:db.close()

if __name__=='__main__':main()
