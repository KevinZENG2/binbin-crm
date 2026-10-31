import datetime as dt
import base64
import importlib.util
import pathlib
import subprocess
import tempfile
import unittest

path = pathlib.Path(__file__).resolve().parents[1] / 'ops/preview/review.py'
spec = importlib.util.spec_from_file_location('review', path)
review = importlib.util.module_from_spec(spec)
spec.loader.exec_module(review)


class ReviewPreviewTest(unittest.TestCase):
    def test_artifact_password_requires_the_separately_held_private_key(self):
        with tempfile.TemporaryDirectory() as directory:
            private = pathlib.Path(directory) / 'private.pem'
            public = pathlib.Path(directory) / 'public.pem'
            subprocess.run(['openssl', 'genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:2048', '-out', str(private)], check=True, capture_output=True)
            public.write_bytes(subprocess.run(['openssl', 'pkey', '-in', str(private), '-pubout'], check=True, capture_output=True).stdout)
            password = 'synthetic-only-password-that-must-not-be-in-artifacts'
            encrypted = review.encrypt_login_password(password, public)
            self.assertNotIn(password, str(encrypted))
            plaintext = subprocess.run(['openssl', 'pkeyutl', '-decrypt', '-inkey', str(private),
                '-pkeyopt', 'rsa_padding_mode:oaep', '-pkeyopt', 'rsa_oaep_md:sha256', '-pkeyopt', 'rsa_mgf1_md:sha256'],
                input=base64.b64decode(encrypted['ciphertext']), check=True, capture_output=True).stdout
            self.assertEqual(plaintext.decode(), password)
            wrong = pathlib.Path(directory) / 'wrong.pem'
            subprocess.run(['openssl', 'genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:2048', '-out', str(wrong)], check=True, capture_output=True)
            result = subprocess.run(['openssl', 'pkeyutl', '-decrypt', '-inkey', str(wrong),
                '-pkeyopt', 'rsa_padding_mode:oaep', '-pkeyopt', 'rsa_oaep_md:sha256', '-pkeyopt', 'rsa_mgf1_md:sha256'],
                input=base64.b64decode(encrypted['ciphertext']), capture_output=True)
            self.assertNotEqual(result.returncode, 0)

    def test_missing_public_key_does_not_fall_back_to_plaintext(self):
        with self.assertRaises(subprocess.CalledProcessError):
            review.encrypt_login_password('synthetic-password', pathlib.Path('/missing/review-access-public.pem'))

    def test_tunnel_announcement_must_use_the_official_https_host(self):
        self.assertEqual(review.origin_from_log('Visit https://sample-review-window.trycloudflare.com |'),
                         'https://sample-review-window.trycloudflare.com')
        for value in ('https://example.com', 'http://sample.trycloudflare.com', 'https://trycloudflare.com', 'https://sample.trycloudflare.com.evil.test'):
            with self.assertRaises(ValueError):
                review.origin_from_log(value)

    def test_public_preview_is_strict_and_has_a_bounded_deadline(self):
        expires = (dt.datetime.now(dt.timezone.utc) + dt.timedelta(hours=2)).isoformat()
        result = review.configuration('CRM_PREVIEW_BASE_URL=https://preview-pending.invalid\nCRM_PREVIEW_ISOLATED=true\n',
                                      'https://sample-review-window.trycloudflare.com', expires)
        self.assertIn('CRM_PREVIEW_COOKIE_SECURE=true\n', result)
        self.assertIn('CRM_PREVIEW_ISOLATED=false\n', result)
        self.assertIn('CRM_REVIEW_EXPIRES_AT=' + expires, result)
        self.assertEqual(result.count('CRM_PREVIEW_BASE_URL='), 1)

    def test_foreign_origins_and_unbounded_deadlines_are_rejected(self):
        now = dt.datetime.now(dt.timezone.utc)
        for expires in ((now - dt.timedelta(seconds=1)).isoformat(), (now + dt.timedelta(hours=3)).isoformat(),
                        '2026-10-29T08:00:00', 'tomorrow'):
            with self.assertRaises(ValueError):
                review.configuration('', 'https://sample.trycloudflare.com', expires)
        for origin in ('http://sample.trycloudflare.com', 'https://example.com',
                       'https://sample.trycloudflare.com/path', 'https://sample.trycloudflare.com\nINJECT=true'):
            with self.assertRaises(ValueError):
                review.configuration('', origin, (now + dt.timedelta(hours=1)).isoformat())


if __name__ == '__main__':
    unittest.main()
