"""Copy scoped secrets to private volumes, then initialize the private S3 fixture."""
import os
import pathlib
import pwd
import shutil
import sys
import time


def copy(source, target, owner, mode=0o600):
    target = pathlib.Path(target)
    target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    if target.exists():
        if target.read_bytes() != pathlib.Path(source).read_bytes():
            raise RuntimeError('Existing preview material differs; explicit rotation is required')
    else:
        with target.open('xb') as output:
            output.write(pathlib.Path(source).read_bytes())
    os.chmod(target, mode)
    os.chown(target, owner, owner)
    os.chown(target.parent, owner, owner)
    os.chmod(target.parent, 0o700)


def state():
    pg_uid = pwd.getpwnam('postgres').pw_uid
    for name in ('postgres_password', 'runtime_password', 'migrator_password'):
        copy('/run/secrets/' + name, '/state/postgres/' + name, pg_uid)
    copy('/run/secrets/postgres_key', '/state/postgres/server.key', pg_uid)
    copy('/config/postgres.crt', '/state/postgres/server.crt', pg_uid, 0o644)
    for name in ('runtime_password', 'migrator_password', 'login_password', 'integration_key', 's3_access_key', 's3_secret_key'):
        copy('/run/secrets/' + name, '/state/app/' + name, 10001)
    copy('/run/secrets/storage_key', '/state/storage/private.key', 10001)
    copy('/config/storage.crt', '/state/storage/public.crt', 10001, 0o644)
    for name in ('s3_access_key', 's3_secret_key'):
        copy('/run/secrets/' + name, '/state/storage/' + name, 10001)
    os.chown('/state/storage-data', 10001, 10001)
    os.chmod('/state/storage-data', 0o700)
    print('CRM_PREVIEW_STATE_READY', flush=True)


def bucket():
    import boto3
    from botocore.config import Config
    from botocore.exceptions import ClientError
    source = pathlib.Path('/state/storage')
    client = boto3.client('s3', endpoint_url='https://storage:9000', region_name='us-east-1',
        aws_access_key_id=(source / 's3_access_key').read_text().strip(),
        aws_secret_access_key=(source / 's3_secret_key').read_text().strip(), verify='/config/root-ca.crt',
        config=Config(s3={'addressing_style': 'path'}, connect_timeout=2, read_timeout=5, retries={'max_attempts': 0}))
    for attempt in range(45):
        try:
            try:
                client.head_bucket(Bucket='crm-preview')
            except ClientError as error:
                if error.response.get('ResponseMetadata', {}).get('HTTPStatusCode') != 404:
                    raise
                client.create_bucket(Bucket='crm-preview')
            probe = 'preview-infrastructure/readback'
            client.put_object(Bucket='crm-preview', Key=probe, Body=b'isolated preview TLS readback')
            assert client.get_object(Bucket='crm-preview', Key=probe)['Body'].read() == b'isolated preview TLS readback'
            print('CRM_PREVIEW_STORAGE_READY: TLS and private readback verified', flush=True)
            return
        except Exception:
            if attempt == 44:
                raise RuntimeError('Private storage initialization failed') from None
            time.sleep(2)


if __name__ == '__main__':
    try:
        {'state': state, 'bucket': bucket}[sys.argv[1]]()
    except Exception:
        raise SystemExit('CRM_PREVIEW_BOOTSTRAP_FAILED: credentials omitted') from None
