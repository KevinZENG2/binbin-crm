"""A bounded cloud acceptance session. Never uses existing customer infrastructure."""
import argparse
import datetime as dt
import hashlib
import http.cookiejar
import json
import os
import pathlib
import re
import signal
import subprocess
import time
import urllib.error
import urllib.request
import uuid

ROOT = pathlib.Path(__file__).resolve().parents[3]
STATE = ROOT / '.crm-preview'
OUTPUT = ROOT / 'review-output'
TUNNEL_RELEASE = '2026.10.5'
TUNNEL_DIGEST = 'e1bc0cf084fd9e590554fe769a86b1db46701c3f847a43d902f7b974a4e16298'
UTC = dt.timezone.utc


def origin_from_log(text):
    match = re.search(r'https://[a-z0-9]+(?:-[a-z0-9]+)*\.trycloudflare\.com(?=[\s|]|$)', text)
    if not match:
        raise ValueError('No official temporary HTTPS origin yet')
    return match.group(0)


def configuration(text, origin, expires):
    if not re.fullmatch(r'https://[a-z0-9]+(?:-[a-z0-9]+)*\.trycloudflare\.com', origin):
        raise ValueError('Only the generated temporary HTTPS origin is allowed')
    deadline = dt.datetime.fromisoformat(expires.replace('Z', '+00:00'))
    if deadline.tzinfo is None:
        raise ValueError('The acceptance deadline must include its time zone')
    now = dt.datetime.now(UTC)
    if not now < deadline <= now + dt.timedelta(hours=2, seconds=10):
        raise ValueError('Acceptance sessions must be bounded to two hours')
    pairs = dict(line.split('=', 1) for line in text.splitlines() if line)
    pairs.update(CRM_PREVIEW_BASE_URL=origin, CRM_PREVIEW_COOKIE_SECURE='true',
                 CRM_PREVIEW_ISOLATED='false', CRM_PREVIEW_PORT='8080', CRM_REVIEW_EXPIRES_AT=expires)
    return ''.join(key + '=' + value + '\n' for key, value in pairs.items())


def private_write(path, text):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'w') as stream:
        stream.write(text)


def compose(*args):
    subprocess.run(['docker', 'compose', '--env-file', str(STATE / 'compose.env'),
                    '-f', 'compose.preview.yaml', '-f', 'compose.review.yaml', *args], cwd=ROOT, check=True)


def start_tunnel():
    executable = STATE / 'cloudflared'
    url = 'https://github.com/cloudflare/cloudflared/releases/download/' + TUNNEL_RELEASE + '/cloudflared-linux-amd64'
    with urllib.request.urlopen(url, timeout=90) as response:
        data = response.read(64 * 1024 * 1024)
    if hashlib.sha256(data).hexdigest() != TUNNEL_DIGEST:
        raise ValueError('Official tunnel executable digest mismatch')
    with executable.open('xb') as stream:
        stream.write(data)
    executable.chmod(0o700)
    log = STATE / 'tunnel.log'
    with log.open('xb') as stream:
        process = subprocess.Popen([str(executable), 'tunnel', '--no-autoupdate', '--url', 'http://127.0.0.1:8080',
                                    '--protocol', 'http2', '--edge-ip-version', '4'],
                                   stdout=stream, stderr=subprocess.STDOUT, start_new_session=True)
    private_write(STATE / 'tunnel.pid', str(process.pid))
    for _ in range(90):
        if process.poll() is not None:
            raise RuntimeError('Temporary tunnel exited before publishing an origin')
        try:
            return origin_from_log(log.read_text(errors='replace'))
        except ValueError:
            time.sleep(2)
    raise TimeoutError('Temporary HTTPS origin timed out')


class Client:
    def __init__(self, origin):
        self.origin, self.csrf, self.tenant = origin, '', ''
        self.cookies = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookies))

    def request(self, path, method='GET', body=None, expected=200, raw=False):
        headers = {'Content-Type': 'application/json', 'Origin': self.origin, 'X-CSRF-Token': self.csrf,
                   'X-Workspace-ID': self.tenant, 'Idempotency-Key': str(uuid.uuid4())}
        request = urllib.request.Request(self.origin + path, method=method, headers=headers,
                                         data=None if body is None else json.dumps(body).encode())
        try:
            response = self.opener.open(request, timeout=30)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            status, data = response.status, response.read(16 * 1024 * 1024)
            if status != expected:
                raise AssertionError('Unexpected status for ' + method + ' ' + path + ': ' + str(status))
            return data if raw else (json.loads(data) if data else None)


def verify(origin, expires):
    version = json.loads((ROOT / 'package.json').read_text())['version']
    client = Client(origin)
    for attempt in range(90):
        try:
            health = client.request('/api/v1/health')
            if health['status'] != 'UP' or health['version'] != version:
                raise AssertionError('Expected the current source version')
            if health.get('preview') != {'expiresAt': expires, 'ephemeral': True, 'syntheticDataOnly': True}:
                raise AssertionError('The public preview must advertise its exact expiry')
            break
        except (OSError, ValueError, AssertionError):
            if attempt == 89:
                raise TimeoutError('Public current-version readiness timed out') from None
            time.sleep(2)
    checks = ['Current Java backend and database are healthy over verified public HTTPS',
              'The public health endpoint advertises the exact temporary deadline']
    page = client.request('/', raw=True)
    if b'<html' not in page.lower():
        raise AssertionError('Live frontend is missing')
    assets = re.findall(rb'(?:src|href)="(/assets/[^"<>]+\.(?:js|css))"', page)
    if len(assets) < 2:
        raise AssertionError('The live frontend assets were not packaged')
    for asset in assets:
        if not client.request(asset.decode(), raw=True):
            raise AssertionError('Empty frontend asset')
    checks.append('The real live frontend and its JavaScript/CSS load through the same HTTPS origin')
    client.request('/api/v1/customers', expected=401)
    password = (STATE / 'secrets/login_password').read_text().strip()
    login = client.request('/api/v1/auth/login', 'POST', {'username': 'owner', 'password': password})
    if not any(cookie.name == 'CRM_SESSION' and cookie.secure and cookie.has_nonstandard_attr('HttpOnly') for cookie in client.cookies):
        raise AssertionError('HTTPS login requires a Secure and HttpOnly session cookie')
    client.csrf = login['csrfToken']
    tenant = '00000000-0000-4000-8000-000000000001'
    active = client.request('/api/v1/auth/active-tenant', 'POST', {'tenantId': tenant})
    client.csrf, client.tenant = active['csrfToken'], tenant
    customers = client.request('/api/v1/customers')
    if customers['total'] != 3:
        raise AssertionError('Only the expected fresh synthetic tenant data is allowed')
    checks.append('Owner login, Secure/HttpOnly cookies and CSRF-protected tenant selection work')
    client.request('/api/v1/customers/00000000-0000-4000-8000-000000000305', expected=404)
    client.request('/api/v1/platform/ai/prompts', expected=403)
    checks.append('Anonymous, cross-tenant and platform-only business boundaries remain enforced')
    product = client.request('/api/v1/products', 'POST', {'sku': 'REVIEW-' + uuid.uuid4().hex[:8],
        'name': '验收用虚构服务', 'kind': 'SERVICE', 'unit': '次', 'priceCents': 10000,
        'costCents': 5000, 'taxRateBps': 1300, 'category': '虚构验收', 'description': '仅测试，不联系客户',
        'status': 'ACTIVE', 'expectedVersion': 0})
    quote = client.request('/api/v1/quotes', 'POST', {'customerId': '00000000-0000-4000-8000-000000000301',
        'title': '临时在线验收报价', 'validUntil': str(dt.date.today() + dt.timedelta(days=30)),
        'notes': '仅测试，不发送给客户', 'expectedVersion': 0, 'lines': [{'productId': product['id'],
        'quantity': '1', 'unitPriceCents': 10000, 'discountBps': 0, 'description': '中文报价明细'}]})
    pdf = client.request('/api/v1/quotes/' + quote['id'] + '/pdf?version=0', raw=True)
    if not pdf.startswith(b'%PDF-') or len(pdf) < 5000:
        raise AssertionError('The real Chinese PDF export must be available')
    checks.append('A synthetic quote exports a real Chinese PDF from the verified private font')
    client.request('/api/v1/auth/logout', 'POST', expected=204)
    operator = Client(origin)
    login = operator.request('/api/v1/auth/login', 'POST', {'username': 'platform-operator', 'password': password})
    operator.csrf = login['csrfToken']
    operator.request('/api/v1/platform/ai/prompts')
    operator.request('/api/v1/auth/logout', 'POST', expected=204)
    checks.append('The platform operator can sign in and access platform administration')
    OUTPUT.mkdir(mode=0o700)
    report = {'status': 'READY', 'version': version, 'origin': origin, 'expiresAt': expires,
              'checkedAt': dt.datetime.now(UTC).isoformat(), 'checks': checks, 'passed': len(checks),
              'sourceSha': os.environ.get('CRM_REVIEW_SOURCE_SHA', ''), 'syntheticDataOnly': True,
              'persistentHosting': False, 'aiProviderEnabled': False}
    private_write(OUTPUT / 'readiness.json', json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    access = {'企业工作台': origin + '/?mode=live&account=owner',
              '平台管理': origin + '/?mode=live&account=platform-operator',
              '企业登录账号': 'owner', '平台登录账号': 'platform-operator', '本次验收登录密码': password,
              '结束时间UTC': expires, '说明': '仅使用测试资料；本次预览限时两小时，结束后测试数据不保留。AI 服务未开启。'}
    private_write(OUTPUT / 'access.json', json.dumps(access, ensure_ascii=False, indent=2) + '\n')
    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        with open(summary, 'a') as stream:
            stream.write('## Temporary CRM acceptance is ready\n\n' + origin + '\n\nExpires: ' + expires +
                         '\n\nSynthetic data only. This is not persistent hosting. Login access is in the private review-access artifact.\n')
    print('CRM_REVIEW_READY: verified HTTPS, login, tenant boundaries and Chinese PDF; private access artifact prepared', flush=True)


def start():
    origin = start_tunnel()
    expires = (dt.datetime.now(UTC) + dt.timedelta(hours=2)).replace(microsecond=0).isoformat().replace('+00:00', 'Z')
    environment = STATE / 'compose.env'
    environment.write_text(configuration(environment.read_text(), origin, expires))
    compose('up', '-d', '--no-build', '--pull', 'never', 'app')
    verify(origin, expires)


def hold():
    report = json.loads((OUTPUT / 'readiness.json').read_text())
    deadline = dt.datetime.fromisoformat(report['expiresAt'].replace('Z', '+00:00'))
    client = Client(report['origin'])
    failures = 0
    while dt.datetime.now(UTC) < deadline:
        try:
            health = client.request('/api/v1/health')
            if health.get('status') != 'UP':
                raise ValueError('Preview unhealthy')
            failures = 0
        except (OSError, ValueError, AssertionError):
            failures += 1
            if failures >= 5:
                raise RuntimeError('Preview lost public readiness for five consecutive checks') from None
        remaining = (deadline - dt.datetime.now(UTC)).total_seconds()
        time.sleep(max(0, min(30, remaining)))
    print('CRM_REVIEW_EXPIRED: the advertised acceptance window ended', flush=True)


def stop():
    pid_file = STATE / 'tunnel.pid'
    if pid_file.exists():
        pid = int(pid_file.read_text())
        try:
            if os.getpgid(pid) == pid:
                os.killpg(pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
    # This workflow runs on a fresh hosted runner. No existing environments or volumes are addressed.
    if (STATE / 'compose.env').exists():
        subprocess.run(['docker', 'compose', '--env-file', str(STATE / 'compose.env'), '-f', 'compose.preview.yaml',
                        'down', '--volumes', '--remove-orphans'], cwd=ROOT, check=True)


if __name__ == '__main__':
    os.umask(0o077)
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=('start', 'hold', 'stop'))
    action = parser.parse_args().action
    try:
        {'start': start, 'hold': hold, 'stop': stop}[action]()
    except Exception as error:
        raise SystemExit('CRM_REVIEW_FAILED: ' + type(error).__name__ + '; private credentials omitted') from None
