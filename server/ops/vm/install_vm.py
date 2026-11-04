"""Install a dedicated short-lived preview on an existing Ubuntu Docker host.

Requires an explicit --apply; no SSH service, password or firewall changes.
The external certificate ZIP remains on this host and is never uploaded.
"""
import argparse
import datetime
import hashlib
import json
import os
import pathlib
import re
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.request
import zipfile

PROJECT = pathlib.Path(__file__).resolve().parents[3]
BUNDLE = PROJECT.parent
DOMAIN = 'crm.jiayunsoft.com'
STACK = 'binbin-crm-vm'
STATE = PROJECT / '.crm-preview'


def run(*args, capture=False, **kwargs):
    return subprocess.run(args, check=True, capture_output=capture, **kwargs)


def sha(path):
    digest = hashlib.sha256()
    with pathlib.Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def read_lock(path):
    lock = json.loads(pathlib.Path(path).read_text())
    if lock.get('version') != '0.23.0' or lock.get('syntheticPreviewOnly') is not True:
        raise ValueError('This installer accepts the 0.23.0 preview bundle only')
    if not re.fullmatch('[0-9a-f]{40}', lock.get('sourceCommit', '')):
        raise ValueError('Invalid source identity')
    if set(lock.get('images', {})) != {'app', 'postgres', 'tools', 'storage'}:
        raise ValueError('Invalid image set')
    if not re.fullmatch('[0-9a-f]{64}', lock.get('imageArchiveSha256', '')):
        raise ValueError('Invalid image archive digest')
    for name, image in lock['images'].items():
        expected = 'binbin-crm-vm-' + name + ':0.23.0-' + lock['sourceCommit'][:12]
        if image.get('tag') != expected or not re.fullmatch('sha256:[0-9a-f]{64}', image.get('id', '')):
            raise ValueError('Invalid immutable image identity')
    return lock


def check_certificate(archive_path, directory):
    directory = pathlib.Path(directory)
    with zipfile.ZipFile(archive_path) as archive:
        if sum(item.file_size for item in archive.infolist()) > 1024 * 1024:
            raise ValueError('Certificate archive is too large')
        certificates, keys = [], []
        for item in archive.infolist():
            if item.is_dir():
                continue
            data = archive.read(item)
            if b'-----BEGIN CERTIFICATE-----' in data:
                certificates.append(data)
            elif b'PRIVATE KEY-----' in data:
                keys.append(data)
        if len(certificates) != 1 or len(keys) != 1:
            raise ValueError('Expected exactly one PEM chain and one private key')
    cert, key = directory / 'fullchain.pem', directory / 'privkey.pem'
    cert.write_bytes(certificates[0]); key.write_bytes(keys[0])
    os.chmod(cert, 0o644); os.chmod(key, 0o600)
    # Some OpenSSL releases return zero for a hostname mismatch: verify the message too.
    host_result = run('openssl', 'x509', '-in', str(cert), '-noout', '-checkhost', DOMAIN, capture=True).stdout
    if b'does match certificate' not in host_result:
        raise ValueError('Certificate hostname mismatch')
    run('openssl', 'x509', '-in', str(cert), '-noout', '-checkend', '259200', capture=True)
    public_cert = run('openssl', 'x509', '-in', str(cert), '-pubkey', '-noout', capture=True).stdout
    public_key = run('openssl', 'pkey', '-in', str(key), '-pubout', capture=True).stdout
    if public_cert != public_key:
        raise ValueError('Certificate and private key mismatch')
    run('openssl', 'verify', '-CApath', '/etc/ssl/certs', '-untrusted', str(cert), str(cert), capture=True)
    return cert, key


def ensure_swap(allow_creation):
    memory = dict((line.split(':', 1)[0], int(line.split()[1])) for line in pathlib.Path('/proc/meminfo').read_text().splitlines() if line.split()[1].isdigit())
    if memory['MemTotal'] < 1900 * 1024:
        raise ValueError('At least 1900 MiB RAM is required for this preview')
    if memory['MemAvailable'] >= 1800 * 1024 or memory['SwapTotal'] >= 1024 * 1024:
        return
    if not allow_creation:
        raise ValueError('Small-memory host: rerun with --create-swap to add a dedicated 2 GiB swap file')
    swap = pathlib.Path('/swapfile-binbin-crm')
    fstab = pathlib.Path('/etc/fstab')
    if swap.exists() or str(swap) in fstab.read_text():
        raise ValueError('Existing CRM swap material requires manual review; nothing overwritten')
    if shutil.disk_usage('/').free < 6 * 1024 ** 3:
        raise ValueError('Less than 6 GiB disk space available')
    fd = os.open(swap, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    os.close(fd)
    run('fallocate', '-l', '2G', str(swap))
    run('mkswap', str(swap), capture=True)
    run('swapon', str(swap))
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    backup = pathlib.Path('/etc/fstab.binbin-crm.' + stamp)
    with backup.open('xb') as output:
        output.write(fstab.read_bytes())
    os.chmod(backup, 0o600)
    with fstab.open('a') as output:
        output.write('\n/swapfile-binbin-crm none swap sw 0 0\n')
    print('Dedicated 2 GiB swap enabled; previous fstab backed up', flush=True)


def compose(*args, capture=False):
    return run('docker', 'compose', '--project-name', STACK, '--env-file', str(STATE / 'compose.env'),
               '-f', str(PROJECT / 'compose.preview.yaml'), '-f', str(PROJECT / 'compose.vm.yaml'),
               *args, cwd=PROJECT, capture=capture)


def wait_for_app():
    for attempt in range(120):
        try:
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            with opener.open('http://127.0.0.1:18080/api/v1/health', timeout=3) as response:
                health = json.load(response)
                if health.get('status') == 'UP' and health.get('version') == '0.23.0':
                    return health
        except Exception:
            pass
        if attempt % 5 == 0:
            identifier = compose('ps', '--all', '--quiet', 'app', capture=True).stdout.decode().strip()
            if identifier:
                state = run('docker', 'inspect', '--format', '{{.State.Status}}', identifier, capture=True).stdout.strip()
                if state in (b'exited', b'dead'):
                    raise RuntimeError('CRM container exited before readiness; inspect its bounded logs')
        time.sleep(2)
    raise RuntimeError('CRM health check timed out')


def install_nginx(cert, key, root=pathlib.Path('/etc/nginx')):
    target = root / 'conf.d/binbin-crm.conf'
    configuration = (PROJECT / 'server/ops/vm/crm.jiayunsoft.com.conf').read_bytes()
    candidates = [root / 'nginx.conf']
    for sites in (root / 'sites-enabled', root / 'conf.d'):
        if sites.exists():
            candidates.extend(sites.iterdir())
    for candidate in candidates:
        if candidate.is_file() and candidate != target:
            text = re.sub(r'(?m)#.*$', '', candidate.read_text(errors='replace'))
            if any(DOMAIN in match.split() for match in re.findall(r'\bserver_name\s+([^;]+);', text)):
                raise ValueError('Existing CRM virtual host requires review: ' + candidate.name)
    if target.exists() and target.read_bytes() != configuration:
        raise ValueError('Existing CRM Nginx configuration differs; nothing overwritten')
    run('nginx', '-t', capture=True)
    certificate_dir = root / 'ssl/binbin-crm'
    certificate_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
    for source in (cert, key):
        destination = certificate_dir / source.name
        if destination.exists():
            if destination.read_bytes() != source.read_bytes():
                raise ValueError('Existing CRM certificate differs; explicit rotation required')
        else:
            with destination.open('xb') as output:
                output.write(source.read_bytes())
            os.chmod(destination, 0o600 if source == key else 0o644)
    created = not target.exists()
    try:
        if created:
            with target.open('xb') as output:
                output.write(configuration)
            os.chmod(target, 0o644)
        run('nginx', '-t', capture=True)
        run('systemctl', 'reload', 'nginx')
    except Exception:
        if created:
            target.unlink(missing_ok=True)
            run('nginx', '-t', capture=True)
        raise
    result = run('curl', '--fail', '--silent', '--show-error', '--noproxy', DOMAIN, '--max-time', '20', '--resolve',
                 DOMAIN + ':443:127.0.0.1', 'https://' + DOMAIN + '/api/v1/health', capture=True)
    if json.loads(result.stdout).get('version') != '0.23.0':
        raise ValueError('HTTPS virtual host did not return the new CRM version')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--cert-zip', required=True, type=pathlib.Path)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--create-swap', action='store_true')
    args = parser.parse_args()
    os.umask(0o077)
    if os.geteuid() != 0:
        raise ValueError('Run this installer as root')
    if 'ID=ubuntu' not in pathlib.Path('/etc/os-release').read_text().splitlines():
        raise ValueError('This installer targets Ubuntu')
    for tool in ('docker', 'nginx', 'openssl', 'curl', 'systemctl'):
        if not shutil.which(tool):
            raise ValueError('Required existing tool not found: ' + tool)
    run('docker', 'compose', 'version', capture=True)
    lock = read_lock(BUNDLE / 'image-lock.json')
    if sha(BUNDLE / 'images.tar.gz') != lock['imageArchiveSha256']:
        raise ValueError('Image archive digest mismatch')
    font_manifest = json.loads((PROJECT / 'server/ops/assets/font-source.json').read_text())
    font = BUNDLE / 'assets/quote-font.ttf'
    if sha(font) != font_manifest['fontSha256']:
        raise ValueError('Bundled PDF font digest mismatch')
    if BUNDLE.is_symlink() or PROJECT.is_symlink():
        raise ValueError('Deployment directory must not be a symlink')
    with tempfile.TemporaryDirectory(prefix='binbin-crm-certificate-') as temporary:
        cert, key = check_certificate(args.cert_zip, temporary)
        if not args.apply:
            print('BUNDLE_AND_CERTIFICATE_OK: no server configuration changed; use --apply to deploy')
            return
        marker = BUNDLE / '.vm-install-owner.json'
        if marker.exists():
            if json.loads(marker.read_text()).get('sourceCommit') != lock['sourceCommit']:
                raise ValueError('Existing deployment belongs to another bundle; explicit upgrade required')
        else:
            with socket.socket() as probe:
                probe.bind(('127.0.0.1', 18080))
            if run('docker', 'ps', '-aq', '--filter', 'label=com.docker.compose.project=' + STACK, capture=True).stdout.strip():
                raise ValueError('Existing CRM stack found outside this installation')
            if run('docker', 'volume', 'ls', '-q', '--filter', 'label=com.docker.compose.project=' + STACK, capture=True).stdout.strip():
                raise ValueError('Existing CRM data volumes found; no initialization performed')
        ensure_swap(args.create_swap)
        if not marker.exists():
            with marker.open('x') as output:
                json.dump({'sourceCommit': lock['sourceCommit']}, output)
        run('docker', 'load', '-i', str(BUNDLE / 'images.tar.gz'))
        for image in lock['images'].values():
            identifier = run('docker', 'image', 'inspect', '--format', '{{.Id}}', image['tag'], capture=True).stdout.decode().strip()
            if identifier != image['id']:
                raise ValueError('Loaded image identity mismatch')
        if not STATE.exists():
            run('python3', str(PROJECT / 'server/ops/preview/prepare.py'), '--base-url', 'https://' + DOMAIN)
        metadata = json.loads((STATE / 'metadata.json').read_text())
        if metadata.get('syntheticPreviewOnly') is not True:
            raise ValueError('Existing state is not a synthetic preview')
        environment = (STATE / 'compose.env').read_text()
        if 'CRM_PREVIEW_BASE_URL=https://' + DOMAIN + '\n' not in environment:
            raise ValueError('Existing origin differs; credentials preserved')
        environment = re.sub(r'(?m)^CRM_PREVIEW_PORT=.*$', 'CRM_PREVIEW_PORT=18080', environment)
        environment = re.sub(r'(?m)^CRM_VM_.*\n?', '', environment)
        for name, image in lock['images'].items():
            environment += 'CRM_VM_' + name.upper() + '_IMAGE=' + image['tag'] + '\n'
        (STATE / 'compose.env').write_text(environment)
        os.chmod(STATE / 'compose.env', 0o600)
        compose('config', '--quiet')
        compose('up', '-d', '--no-build', '--pull', 'never')
        health = wait_for_app()
        compose('run', '--rm', '--no-deps', '--entrypoint', 'python3',
                '--volume', str(font) + ':/opt/crm-font.ttf:ro',
                '--volume', str(PROJECT / 'server/ops/vm/publish_vm_font.py') + ':/opt/crm-publish-font.py:ro',
                'storage-init', '/opt/crm-publish-font.py')
        install_nginx(cert, key)
        report = {'checkedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'health': health,
                  'sourceCommit': lock['sourceCommit'], 'localHttpsVerified': True, 'publicDnsAndAccessVerified': False,
                  'quoteFontPublished': True,
                  'syntheticPreviewOnly': True, 'internalCertificateDays': 30, 'url': 'https://' + DOMAIN}
        (BUNDLE / 'deployment-status.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        print('LOCAL_HTTPS_READY: https://' + DOMAIN + '; verify public DNS and external access next')
        print('Admin username: owner; retrieve initial password locally from ' + str(STATE / 'secrets/login_password'))
        print('Short-lived synthetic preview only; internal certificates expire in 30 days; AI remains disabled')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # Never expose certificate contents, passwords or subprocess output.
        raise SystemExit('CRM_VM_INSTALL_FAILED: ' + type(error).__name__ + ': ' + str(error)) from None
