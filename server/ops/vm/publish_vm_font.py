"""Publish only the bundled public PDF font to this preview's private storage."""
import hashlib
import pathlib
import boto3
from botocore.config import Config

EXPECTED = '12a7fef9f024f411ed7e2d89a614b9865f14ae4129ad4993a7e117f36322a794'


def main():
    font = pathlib.Path('/opt/crm-font.ttf').read_bytes()
    if hashlib.sha256(font).hexdigest() != EXPECTED:
        raise ValueError('Bundled PDF font digest mismatch')
    state = pathlib.Path('/state/storage')
    client = boto3.client('s3', endpoint_url='https://storage:9000', region_name='us-east-1',
        aws_access_key_id=(state / 's3_access_key').read_text().strip(),
        aws_secret_access_key=(state / 's3_secret_key').read_text().strip(), verify='/config/root-ca.crt',
        config=Config(s3={'addressing_style': 'path'}, connect_timeout=5, read_timeout=40, retries={'max_attempts': 0}))
    key = 'system/fonts/noto-sans-sc-' + EXPECTED + '.ttf'
    client.put_object(Bucket='crm-preview', Key=key, Body=font, ContentType='font/ttf', Metadata={'sha256': EXPECTED, 'license': 'SIL-OFL-1.1'})
    if hashlib.sha256(client.get_object(Bucket='crm-preview', Key=key)['Body'].read()).hexdigest() != EXPECTED:
        raise ValueError('Published PDF font readback mismatch')
    print('CRM_VM_PDF_FONT_READY: HTTPS readback verified; no business records touched')


if __name__ == '__main__':
    try:
        main()
    except Exception:
        raise SystemExit('CRM_VM_PDF_FONT_FAILED: credentials omitted') from None
