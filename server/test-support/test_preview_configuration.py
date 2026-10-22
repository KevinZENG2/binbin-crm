"""Exercise real temporary certificates and refuse accidental credential replacement."""
import contextlib
import importlib.util
import io
import pathlib
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('preview_prepare', pathlib.Path(__file__).parents[1] / 'ops/preview/prepare.py')
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)


class PreviewConfigurationTests(unittest.TestCase):
    def test_certificates_validate_hosts_and_secrets_stay_private(self):
        with tempfile.TemporaryDirectory() as folder:
            state = pathlib.Path(folder) / 'private'
            output = io.StringIO()
            with patch.object(prepare, 'STATE', state), patch('sys.argv', ['prepare']), contextlib.redirect_stdout(output):
                prepare.main()
            for host in ('postgres', 'storage'):
                subprocess.run(['openssl', 'verify', '-CAfile', str(state / 'root-ca.crt'), '-verify_hostname', host, str(state / (host + '.crt'))], check=True, capture_output=True)
            wrong = subprocess.run(['openssl', 'verify', '-CAfile', str(state / 'root-ca.crt'), '-verify_hostname', 'untrusted.example.test', str(state / 'postgres.crt')], capture_output=True)
            self.assertNotEqual(wrong.returncode, 0)
            self.assertEqual(state.stat().st_mode & 0o777, 0o700)
            self.assertIn('CRM_PREVIEW_ISOLATED=true\n', (state / 'compose.env').read_text())
            for file in (state / 'secrets').iterdir():
                self.assertEqual(file.stat().st_mode & 0o777, 0o600)
                self.assertNotIn(file.read_text().strip(), output.getvalue())

    def test_second_preparation_preserves_every_existing_credential(self):
        with tempfile.TemporaryDirectory() as folder:
            state = pathlib.Path(folder) / 'private'
            with patch.object(prepare, 'STATE', state), patch('sys.argv', ['prepare']), contextlib.redirect_stdout(io.StringIO()):
                prepare.main()
                before = {str(p.relative_to(state)): p.read_bytes() for p in state.rglob('*') if p.is_file()}
                with self.assertRaises(SystemExit):
                    prepare.main()
                self.assertEqual(before, {str(p.relative_to(state)): p.read_bytes() for p in state.rglob('*') if p.is_file()})

    def test_remote_http_and_environment_injection_are_rejected_before_writing(self):
        for origin in ('http://preview.example.test', 'https://ok.example.test\nCRM_AI_ENABLED=true', 'https://ok.example.test/$TOKEN', 'https://user:password@example.test', 'https://ok.example.test:99999'):
            with tempfile.TemporaryDirectory() as folder:
                state = pathlib.Path(folder) / 'private'
                with patch.object(prepare, 'STATE', state), patch('sys.argv', ['prepare', '--base-url', origin]), self.assertRaises(SystemExit):
                    prepare.main()
                self.assertFalse(state.exists())

    def test_https_cloud_configuration_keeps_isolated_bypass_off(self):
        with tempfile.TemporaryDirectory() as folder:
            state = pathlib.Path(folder) / 'private'
            with patch.object(prepare, 'STATE', state), patch('sys.argv', ['prepare', '--base-url', 'https://preview.example.test']), contextlib.redirect_stdout(io.StringIO()):
                prepare.main()
            config = (state / 'compose.env').read_text()
            self.assertIn('CRM_PREVIEW_ISOLATED=false\n', config)
            self.assertIn('CRM_PREVIEW_COOKIE_SECURE=true\n', config)


if __name__ == '__main__':
    unittest.main()
