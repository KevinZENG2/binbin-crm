"""Meaningful preflight/rollback tests; never modify the host Nginx or swap."""
import importlib.util
import json
import pathlib
import subprocess
import tempfile
import tarfile
import unittest
from unittest import mock
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('vm_install', ROOT / 'server/ops/vm/install_vm.py')
vm = importlib.util.module_from_spec(spec)
spec.loader.exec_module(vm)
package_spec = importlib.util.spec_from_file_location('vm_package', ROOT / 'server/ops/vm/package_vm.py')
package = importlib.util.module_from_spec(package_spec)
package_spec.loader.exec_module(package)


class VmSafety(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temporary.name)

    def tearDown(self):
        self.temporary.cleanup()

    def lock(self):
        commit = 'a' * 40
        return {'version': '0.23.0', 'sourceCommit': commit, 'syntheticPreviewOnly': True,
                'imageArchiveSha256': 'b' * 64,
                'images': {name: {'tag': 'binbin-crm-vm-' + name + ':0.23.0-' + commit[:12], 'id': 'sha256:' + 'c' * 64}
                           for name in ('app', 'postgres', 'tools', 'storage')}}

    def test_tampered_image_tag_is_rejected_before_docker(self):
        lock = self.lock()
        lock['images']['app']['tag'] = 'untrusted.invalid/arbitrary:latest'
        path = self.root / 'lock.json'
        path.write_text(json.dumps(lock))
        with self.assertRaises(ValueError), mock.patch.object(vm, 'run') as runner:
            vm.read_lock(path)
        runner.assert_not_called()

    def test_missing_image_digest_is_rejected(self):
        lock = self.lock()
        del lock['images']['postgres']['id']
        path = self.root / 'lock.json'
        path.write_text(json.dumps(lock))
        with self.assertRaises(ValueError):
            vm.read_lock(path)

    def test_bundle_files_cannot_inherit_runner_ownership_or_writable_modes(self):
        info = tarfile.TarInfo('binbin-crm-preview/project/install.py')
        info.uid, info.gid, info.mode = 1001, 1001, 0o6777
        normalized = package.root_owned(info)
        self.assertEqual((normalized.uid, normalized.gid, normalized.mode), (0, 0, 0o755))
        link = tarfile.TarInfo('link')
        link.type = tarfile.SYMTYPE
        link.linkname = '/etc/shadow'
        with self.assertRaises(RuntimeError):
            package.root_owned(link)

    def certificate_archive(self, hostname, other_key=False):
        certificate = self.root / 'certificate.pem'
        key = self.root / 'private.key'
        subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '5',
                        '-subj', '/CN=' + hostname, '-addext', 'subjectAltName=DNS:' + hostname,
                        '-keyout', str(key), '-out', str(certificate)], check=True, capture_output=True)
        if other_key:
            subprocess.run(['openssl', 'genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:2048',
                            '-out', str(key)], check=True, capture_output=True)
        archive = self.root / 'certificate.zip'
        with zipfile.ZipFile(archive, 'w') as output:
            output.write(certificate, 'certificate.pem')
            output.write(key, 'private.key')
        destination = self.root / 'unpacked'
        destination.mkdir()
        return archive, destination

    def test_wrong_hostname_certificate_is_rejected(self):
        archive, destination = self.certificate_archive('unrelated.invalid')
        with self.assertRaises((ValueError, subprocess.CalledProcessError)):
            vm.check_certificate(archive, destination)

    def test_mismatched_private_key_is_rejected(self):
        archive, destination = self.certificate_archive(vm.DOMAIN, other_key=True)
        with self.assertRaisesRegex(ValueError, 'private key mismatch'):
            vm.check_certificate(archive, destination)

    def test_untrusted_self_signed_certificate_is_rejected(self):
        archive, destination = self.certificate_archive(vm.DOMAIN)
        with self.assertRaises(subprocess.CalledProcessError):
            vm.check_certificate(archive, destination)

    def test_oversized_certificate_archive_is_rejected(self):
        archive = self.root / 'large.zip'
        with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_DEFLATED) as output:
            output.writestr('bomb.pem', b'x' * (1024 * 1024 + 1))
        with self.assertRaisesRegex(ValueError, 'too large'):
            vm.check_certificate(archive, self.root)

    def nginx_fixture(self):
        root = self.root / 'nginx'
        (root / 'conf.d').mkdir(parents=True)
        (root / 'sites-enabled').mkdir()
        existing = root / 'sites-enabled/other-service'
        existing.write_text('server { listen 443 ssl; server_name existing.invalid; }\n')
        cert, key = self.root / 'fullchain.pem', self.root / 'privkey.pem'
        cert.write_bytes(b'fixture certificate')
        key.write_bytes(b'fixture private key')
        return root, existing, cert, key

    def test_nginx_validation_failure_removes_only_the_new_virtual_host(self):
        root, existing, cert, key = self.nginx_fixture()
        before = existing.read_bytes()
        calls = []

        def runner(*args, **kwargs):
            calls.append(args)
            if len(calls) == 2:
                self.assertTrue((root / 'conf.d/binbin-crm.conf').exists())
                raise subprocess.CalledProcessError(1, args)
            return subprocess.CompletedProcess(args, 0, stdout=b'')

        with mock.patch.object(vm, 'run', side_effect=runner), self.assertRaises(subprocess.CalledProcessError):
            vm.install_nginx(cert, key, root=root)
        self.assertFalse((root / 'conf.d/binbin-crm.conf').exists())
        self.assertEqual(existing.read_bytes(), before)
        self.assertFalse(any(call[:2] == ('systemctl', 'reload') for call in calls))

    def test_existing_crm_virtual_host_is_preserved(self):
        root, existing, cert, key = self.nginx_fixture()
        existing.write_text('server { server_name crm.jiayunsoft.com; }\n')
        before = existing.read_bytes()
        with mock.patch.object(vm, 'run') as runner, self.assertRaisesRegex(ValueError, 'Existing CRM virtual host'):
            vm.install_nginx(cert, key, root=root)
        self.assertEqual(existing.read_bytes(), before)
        runner.assert_not_called()


if __name__ == '__main__':
    unittest.main()
