import base64
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('signing', Path(__file__).with_name('preview-signing.py'))
signing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(signing)

class PreviewSigningTest(unittest.TestCase):
    def test_required_key_cannot_silently_fall_back(self):
        with self.assertRaises(ValueError):
            signing.prepare({'WE_PREVIEW_SIGNING_REQUIRED': 'true'})

    def test_partial_configuration_is_rejected(self):
        with self.assertRaises(ValueError):
            signing.prepare({'WE_PREVIEW_KEY_ALIAS': 'test'})

    def test_key_stays_private_and_outside_checkout(self):
        with tempfile.TemporaryDirectory() as directory:
            env = {name: 'synthetic' for name in signing.FIELDS}
            env.update(WE_PREVIEW_KEYSTORE_B64=base64.b64encode(b'fixture').decode(),
                       WE_PREVIEW_CERT_SHA256='aa:' * 31 + 'aa', RUNNER_TEMP=directory,
                       GITHUB_ENV=str(Path(directory) / 'env'))
            signing.prepare(env)
            key = Path(directory) / 'we-preview-signing.jks'
            self.assertEqual(key.read_bytes(), b'fixture')
            self.assertEqual(key.stat().st_mode & 0o777, 0o600)
            self.assertIn(str(key), Path(env['GITHUB_ENV']).read_text())

    def test_wrong_certificate_prevents_delivery(self):
        env = {'WE_PREVIEW_KEYSTORE_PATH': '/private/key', 'ANDROID_HOME': '/sdk',
               'WE_PREVIEW_CERT_SHA256': 'a' * 64}
        with patch.object(signing.subprocess, 'check_output', return_value='Signer #1 certificate SHA-256 digest: ' + 'b' * 64):
            with self.assertRaises(ValueError):
                signing.verify(env, 'fixture.apk')

    def test_matching_certificate_is_accepted(self):
        env = {'WE_PREVIEW_KEYSTORE_PATH': '/private/key', 'ANDROID_HOME': '/sdk',
               'WE_PREVIEW_CERT_SHA256': 'a' * 64}
        with patch.object(signing.subprocess, 'check_output', return_value='Signer #1 certificate SHA-256 digest: ' + 'a' * 64):
            signing.verify(env, 'fixture.apk')

if __name__ == '__main__':
    unittest.main()
