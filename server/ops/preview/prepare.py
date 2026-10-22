"""Create a fresh private preview configuration; never overwrite existing keys."""
import argparse
import datetime
import json
import os
import pathlib
import re
import secrets
import subprocess
import urllib.parse

ROOT = pathlib.Path(__file__).resolve().parents[3]
STATE = ROOT / '.crm-preview'


def command(*args):
    subprocess.run(args, check=True, capture_output=True)


def write(path, data, mode=0o600):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, mode)
    with os.fdopen(descriptor, 'w') as stream:
        stream.write(data)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--base-url', default='http://127.0.0.1:8080')
    args = parser.parse_args()
    if not re.fullmatch(r'https?://[A-Za-z0-9.-]+(?::[0-9]{1,5})?/?', args.base_url):
        raise SystemExit('Invalid preview origin')
    url = urllib.parse.urlsplit(args.base_url)
    local = url.hostname in ('127.0.0.1', 'localhost')
    if (url.scheme != 'https' and not (local and url.scheme == 'http')) or not url.hostname or url.username or url.password or url.path not in ('', '/') or url.query or url.fragment:
        raise SystemExit('Use a HTTPS origin, or HTTP localhost for an isolated preview')
    base_url = args.base_url.rstrip('/')
    # Resolve the port now so malformed values cannot reach generated Compose input.
    try:
        port = url.port or (443 if url.scheme == 'https' else 80)
    except ValueError:
        raise SystemExit('Invalid preview URL port') from None
    if STATE.exists():
        raise SystemExit('Preview configuration already exists; existing credentials were preserved')
    STATE.mkdir(mode=0o700)
    key_dir = STATE / 'secrets'
    key_dir.mkdir(mode=0o700)
    for name in ('postgres_password', 'runtime_password', 'migrator_password', 'login_password', 's3_access_key', 's3_secret_key'):
        write(key_dir / name, secrets.token_hex(24) + '\n')
    write(key_dir / 'integration_key', secrets.token_hex(32) + '\n')
    command('openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-sha256', '-days', '30', '-subj', '/CN=CRM isolated preview root', '-keyout', str(key_dir / 'ca_key'), '-out', str(STATE / 'root-ca.crt'))
    os.chmod(key_dir / 'ca_key', 0o600)
    os.chmod(STATE / 'root-ca.crt', 0o644)
    for name, sans in (('postgres', 'DNS:postgres'), ('storage', 'DNS:storage,DNS:*.storage')):
        key, csr, cert = key_dir / (name + '_key'), STATE / (name + '.csr'), STATE / (name + '.crt')
        command('openssl', 'req', '-new', '-newkey', 'rsa:2048', '-nodes', '-subj', '/CN=' + name, '-keyout', str(key), '-out', str(csr))
        os.chmod(key, 0o600)
        extension = STATE / (name + '.ext')
        write(extension, 'subjectAltName=' + sans + '\nextendedKeyUsage=serverAuth\nbasicConstraints=CA:FALSE\n')
        command('openssl', 'x509', '-req', '-in', str(csr), '-CA', str(STATE / 'root-ca.crt'), '-CAkey', str(key_dir / 'ca_key'), '-set_serial', '0x' + secrets.token_hex(16), '-out', str(cert), '-days', '30', '-sha256', '-extfile', str(extension))
        os.chmod(cert, 0o644)
        csr.unlink()
        extension.unlink()
    write(STATE / 'compose.env', 'CRM_PREVIEW_BASE_URL=' + base_url + '\nCRM_PREVIEW_COOKIE_SECURE=' + str(url.scheme == 'https').lower() + '\nCRM_PREVIEW_PORT=' + str(port if local else 8080) + '\n')
    write(STATE / 'metadata.json', json.dumps({'createdAt': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'syntheticPreviewOnly': True, 'certificateDays': 30}, indent=2) + '\n')
    print('CRM_PREVIEW_PREPARED: private files created; no credential values printed')


if __name__ == '__main__':
    try:
        main()
    except subprocess.CalledProcessError:
        raise SystemExit('Preview certificate preparation failed; preserve the private directory for diagnosis') from None
