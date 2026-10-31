"""Real Docker preview acceptance. Local synthetic stack only, no cloud credentials."""
import datetime
import http.cookiejar
import json
import os
import pathlib
import subprocess
import time
import urllib.error
import urllib.request
import uuid

assert os.environ.get('CRM_PORTABLE_ACCEPTANCE') == 'true', 'Explicit isolated acceptance required'
ROOT = pathlib.Path(__file__).resolve().parents[2]
BASE = os.environ.get('CRM_PORTABLE_BASE_URL', 'http://127.0.0.1:8080')
assert BASE in ('http://127.0.0.1:8080', 'http://127.0.0.1:18080'), 'Acceptance is restricted to local loopback'
COMPOSE = ['docker', 'compose', '--env-file', '.crm-preview/compose.env', '-f', 'compose.preview.yaml']
if os.environ.get('CRM_PORTABLE_VM_ACCEPTANCE') == 'true':
    COMPOSE += ['-f', 'compose.vm.yaml']
PASSWORD = (ROOT / '.crm-preview/secrets/login_password').read_text().strip()
checks = []


def check(name, condition=True):
    if not condition:
        raise AssertionError(name)
    checks.append(name)
    print('PASS ' + name, flush=True)


def uid(n):
    return '00000000-0000-4000-8000-' + str(n).zfill(12)


def wait():
    for attempt in range(120):
        try:
            with urllib.request.urlopen(BASE + '/api/v1/health', timeout=3) as response:
                body = json.load(response)
                if body.get('status') == 'UP':
                    return body
        except Exception:
            pass
        if attempt % 5 == 0:
            identifier = subprocess.check_output(COMPOSE + ['ps', '--all', '--quiet', 'app'], cwd=ROOT, text=True).strip()
            if identifier:
                state = subprocess.check_output(['docker', 'inspect', '--format', '{{.State.Status}}', identifier], text=True).strip()
                if state in ('exited', 'dead'):
                    raise AssertionError('Application exited before readiness; inspect redacted diagnostics')
        time.sleep(2)
    raise AssertionError('Preview application did not become healthy')


def sql(statement, role='crm_runtime'):
    assert role in ('crm_runtime', 'postgres')
    file = 'runtime_password' if role == 'crm_runtime' else 'postgres_password'
    shell = 'PGPASSWORD=$(cat /etc/crm-preview/' + file + '); export PGPASSWORD; export PGSSLMODE=verify-full PGSSLROOTCERT=/opt/crm-preview-ca.crt; exec psql -X -qAt -v ON_ERROR_STOP=1 -h postgres -U ' + role + ' -d crm_preview'
    return subprocess.check_output(COMPOSE + ['exec', '-T', '--user', 'postgres', 'postgres', 'sh', '-c', shell], cwd=ROOT, input=statement, text=True).strip()


class Client:
    def __init__(self, username=None):
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.csrf = ''
        self.tenant = ''
        if username:
            data = self.request('/auth/login', 'POST', {'username': username, 'password': PASSWORD})
            self.csrf = data['csrfToken']
            if username != 'platform-operator':
                self.switch(1)

    def switch(self, number):
        self.tenant = ''
        data = self.request('/auth/active-tenant', 'POST', {'tenantId': uid(number)})
        self.csrf = data['csrfToken']
        self.tenant = uid(number)

    def request(self, path, method='GET', body=None, status=200, content_type='application/json', raw=False):
        data = body if isinstance(body, bytes) else None if body is None else json.dumps(body).encode()
        headers = {'Content-Type': content_type, 'X-CSRF-Token': self.csrf, 'X-Workspace-ID': self.tenant, 'Idempotency-Key': str(uuid.uuid4())}
        request = urllib.request.Request(BASE + '/api/v1' + path, data=data, headers=headers, method=method)
        try:
            with self.opener.open(request, timeout=40) as response:
                code, result = response.status, response.read()
        except urllib.error.HTTPError as error:
            code, result = error.code, error.read()
        if code != status:
            raise AssertionError(method + ' ' + path + ': expected ' + str(status) + ', got ' + str(code))
        return result if raw else json.loads(result) if result else None


try:
    check('Containerized 0.23.0 serves a ready database-backed health endpoint', wait()['version'] == '0.23.0')
    if os.environ.get('CRM_PORTABLE_VM_ACCEPTANCE') == 'true':
        for service, limit in (('app', 1024 ** 3), ('postgres', 256 * 1024 ** 2), ('storage', 256 * 1024 ** 2)):
            identifier = subprocess.check_output(COMPOSE + ['ps', '--quiet', service], cwd=ROOT, text=True).strip()
            configuration = json.loads(subprocess.check_output(['docker', 'inspect', '--format', '{{json .HostConfig}}', identifier], text=True))
            assert configuration['Memory'] == limit, 'VM memory limit not applied'
            if service == 'app':
                assert configuration['PortBindings']['8080/tcp'] == [{'HostIp': '127.0.0.1', 'HostPort': '18080'}]
            else:
                assert not configuration.get('PortBindings'), 'Infrastructure ports must stay private'
        check('VM resource limits and loopback-only application port are enforced')
    user = subprocess.check_output(COMPOSE + ['exec', '-T', 'app', 'id', '-u'], cwd=ROOT, text=True).strip()
    check('Java application runs as non-root user 10001', user == '10001')
    check('Database migration reached V30', sql('SELECT max(version::int) FROM flyway_schema_history WHERE success;', 'postgres') == '30')
    check('Runtime database connection validates TLS', sql('SELECT ssl FROM pg_stat_ssl WHERE pid=pg_backend_pid();') == 't')
    check('Runtime role has no superuser, RLS bypass, role creation or database creation', sql("SELECT rolsuper,rolbypassrls,rolcreaterole,rolcreatedb FROM pg_roles WHERE rolname=current_user;") == 'f|f|f|f')
    check('Runtime sees no customer rows without a tenant context', sql('SELECT count(*) FROM crm_customer;') == '0')
    check('Runtime tenant context selects only its own synthetic rows', sql("BEGIN; SET LOCAL crm.tenant_id='" + uid(1) + "'; SELECT count(*) FROM crm_customer; ROLLBACK;") == '3')
    unsafe = sql("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relrowsecurity AND (NOT c.relforcerowsecurity OR pg_get_userbyid(c.relowner)<>'crm_migrator');", 'postgres')
    check('Business RLS is forced and owned by the migration role', unsafe == '0')
    anonymous = Client()
    anonymous.request('/customers', status=401)
    anonymous.request('/ai/tasks', status=401)
    check('Anonymous business and AI requests remain protected')
    owner, bob, platform = Client('owner'), Client('bob'), Client('platform-operator')
    check('Enterprise login and tenant selection work', owner.request('/customers')['total'] == 3)
    owner.request('/customers/' + uid(305), status=404)
    owner.request('/platform/ai/prompts', status=403)
    check('Cross-tenant records and platform administration stay isolated')
    tasks = owner.request('/ai/tasks')['items']
    check('All twelve P8 scenarios are present in the container', len(tasks) == 12)
    releases = platform.request('/platform/ai/prompts')['releases']
    check('New AI scenarios remain closed before a pilot release', sum(1 for release in releases if not release['enabled']) >= 9)
    boundary = 'crm-preview-' + uuid.uuid4().hex
    content = b'Private portable preview attachment; synthetic data only.\n'
    multipart = ('--' + boundary + '\r\nContent-Disposition: form-data; name="customerId"\r\n\r\n' + uid(301) + '\r\n--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="portable-preview.txt"\r\nContent-Type: text/plain\r\n\r\n').encode() + content + ('\r\n--' + boundary + '--\r\n').encode()
    attachment = owner.request('/attachments', 'POST', multipart, content_type='multipart/form-data; boundary=' + boundary)
    download = '/attachments/' + attachment['id'] + '/download'
    check('Java S3 client uploads and downloads through verified HTTPS', owner.request(download, raw=True) == content)
    anonymous.request(download, status=401)
    bob.request(download, status=404)
    owner.switch(2)
    owner.request(download, status=404)
    owner.switch(1)
    check('Private attachments enforce authentication, record and tenant boundaries')
    subprocess.run(COMPOSE + ['stop', 'app'], cwd=ROOT, check=True, capture_output=True)
    subprocess.run(COMPOSE + ['restart', 'postgres', 'storage'], cwd=ROOT, check=True, capture_output=True)
    subprocess.run(COMPOSE + ['up', '-d', '--no-build', 'app'], cwd=ROOT, check=True, capture_output=True)
    wait()
    check('Database, session and private attachment survive a stack restart', owner.request(download, raw=True) == content and owner.request('/customers')['total'] == 3)
    result = {'checkedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'applicationVersion': '0.23.0', 'migration': 30, 'syntheticOnly': True, 'cloudDeployment': False, 'checks': checks, 'passed': len(checks), 'status': 'SUCCESS'}
    (ROOT / 'portable-preview-acceptance.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print('CRM_PORTABLE_PREVIEW_ACCEPTANCE ' + json.dumps({'passed': len(checks), 'status': 'SUCCESS'}), flush=True)
except Exception as error:
    # Never print response bodies, cookies, passwords, SQL variables or command output.
    print('CRM_PORTABLE_PREVIEW_FAILED: ' + type(error).__name__, flush=True)
    raise
