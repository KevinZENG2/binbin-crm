"""Strict local TLS test for the VM proxy; all accounts and certificates synthetic."""
import datetime
import json
import os
import pathlib
import subprocess
import time
import uuid

ROOT = pathlib.Path(__file__).resolve().parents[2]
STATE = ROOT / '.crm-preview'
os.umask(0o077)
csrf = ''
tenant = ''
checks = []


def request(path, method='GET', body=None, expected=200):
    header_file = STATE / 'https-response.headers'
    cookie_file = STATE / 'https-test.cookies'
    args = ['curl', '--silent', '--show-error', '--max-time', '15', '--cacert', str(STATE / 'root-ca.crt'),
            '--resolve', 'crm.jiayunsoft.com:443:127.0.0.1', '--cookie-jar', str(cookie_file), '--cookie', str(cookie_file),
            '--dump-header', str(header_file), '--write-out', '\n%{http_code}', '-X', method,
            '-H', 'Content-Type: application/json', '-H', 'X-CSRF-Token: ' + csrf,
            '-H', 'X-Workspace-ID: ' + tenant, '-H', 'Idempotency-Key: ' + str(uuid.uuid4()),
            'https://crm.jiayunsoft.com' + path]
    data = None
    if body is not None:
        args += ['--data-binary', '@-']
        data = json.dumps(body).encode()
    result = subprocess.run(args, input=data, capture_output=True, check=True)
    value, status = result.stdout.rsplit(b'\n', 1)
    if int(status) != expected:
        raise AssertionError('Unexpected HTTPS status for ' + method + ' ' + path + ': ' + status.decode())
    return value, header_file.read_text()


def check(name, condition):
    if not condition:
        raise AssertionError(name)
    checks.append(name)
    print('PASS ' + name, flush=True)


for attempt in range(90):
    try:
        health, _ = request('/api/v1/health')
        check('Strict HTTPS returns CRM 0.23.0 after disabling isolated acceptance', json.loads(health)['version'] == '0.23.0')
        break
    except Exception:
        if attempt == 89:
            raise AssertionError('HTTPS readiness timed out') from None
        time.sleep(2)

request('/api/v1/customers', expected=401)
check('Anonymous business requests stay protected behind Nginx', True)
password = (STATE / 'secrets/login_password').read_text().strip()
login, headers = request('/api/v1/auth/login', 'POST', {'username': 'owner', 'password': password})
csrf = json.loads(login)['csrfToken']
cookies = [line.lower() for line in headers.splitlines() if line.lower().startswith('set-cookie:')]
check('HTTPS login issues a Secure and HttpOnly session cookie', any('secure' in line and 'httponly' in line for line in cookies))
workspace = '00000000-0000-4000-8000-000000000001'
active, _ = request('/api/v1/auth/active-tenant', 'POST', {'tenantId': workspace})
csrf, tenant = json.loads(active)['csrfToken'], workspace
customers, _ = request('/api/v1/customers')
check('Secure-cookie login and CSRF-protected tenant selection work', json.loads(customers)['total'] == 3)
request('/api/v1/customers/00000000-0000-4000-8000-000000000305', expected=404)
request('/api/v1/platform/ai/prompts', expected=403)
check('Cross-tenant records and platform administration remain isolated over HTTPS', True)
page, _ = request('/')
check('The live frontend is served through the same HTTPS virtual host', b'<html' in page.lower())
report = {'checkedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'passed': len(checks),
          'checks': checks, 'status': 'SUCCESS', 'syntheticOnly': True, 'actualServerDeployed': False,
          'certificateVerification': 'required', 'secureCookie': True, 'isolatedAcceptance': False}
(ROOT / 'vm-https-acceptance.json').write_text(json.dumps(report, indent=2) + '\n')
print('CRM_VM_HTTPS_ACCEPTANCE ' + json.dumps({'passed': len(checks), 'status': 'SUCCESS'}))
