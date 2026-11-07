#!/usr/bin/env python3
"""Prepare private CI signing material and verify the APK's public certificate."""
import base64
import os
from pathlib import Path
import re
import subprocess
import sys

FIELDS = ('WE_PREVIEW_KEYSTORE_B64', 'WE_PREVIEW_STORE_PASSWORD',
          'WE_PREVIEW_KEY_ALIAS', 'WE_PREVIEW_KEY_PASSWORD')

def fingerprint(value):
    value = value.replace(':', '').strip().lower()
    if not re.fullmatch(r'[0-9a-f]{64}', value):
        raise ValueError('Expected a SHA-256 certificate fingerprint')
    return value

def prepare(env):
    supplied = [bool(env.get(name)) for name in FIELDS]
    if not any(supplied):
        if env.get('WE_PREVIEW_SIGNING_REQUIRED', '').lower() == 'true':
            raise ValueError('Persistent preview signing is required but no key is configured')
        print('No persistent preview key: test-only signing; no upgrade guarantee')
        return
    if not all(supplied):
        raise ValueError('Persistent preview signing configuration is incomplete')
    fingerprint(env.get('WE_PREVIEW_CERT_SHA256', ''))
    data = base64.b64decode(env[FIELDS[0]], validate=True)
    if not data:
        raise ValueError('Signing keystore is empty')
    path = Path(env['RUNNER_TEMP']) / 'we-preview-signing.jks'
    # Never put the key under the checkout or any uploaded artifact directory.
    with open(path, 'wb', opener=lambda p, flags: os.open(p, flags, 0o600)) as out:
        os.chmod(path, 0o600)
        out.write(data)
    with open(env['GITHUB_ENV'], 'a') as out:
        out.write(f'WE_PREVIEW_KEYSTORE_PATH={path}\n')
    print('Persistent preview signing prepared')

def verify(env, apk):
    if not env.get('WE_PREVIEW_KEYSTORE_PATH'):
        if env.get('WE_PREVIEW_SIGNING_REQUIRED', '').lower() == 'true':
            raise ValueError('Persistent signing key was not prepared')
        print('Test-only APK; persistent certificate verification not enabled')
        return
    expected = fingerprint(env.get('WE_PREVIEW_CERT_SHA256', ''))
    signer = Path(env['ANDROID_HOME']) / 'build-tools/34.0.0/apksigner'
    output = subprocess.check_output([str(signer), 'verify', '--print-certs', apk], text=True)
    actual = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F:]+)', output)
    if len(actual) != 1 or fingerprint(actual[0]) != expected:
        raise ValueError('APK signing certificate does not match the configured identity')
    print('Verified persistent preview certificate: ' + expected)

if __name__ == '__main__':
    try:
        if sys.argv[1:] == ['prepare']:
            prepare(os.environ)
        elif len(sys.argv) == 3 and sys.argv[1] == 'verify':
            verify(os.environ, sys.argv[2])
        else:
            raise ValueError('Usage: preview-signing.py prepare | verify APK')
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError):
        # Do not echo input values, decoded key bytes or subprocess command arguments.
        sys.exit('Preview signing failed: check the complete key configuration and public certificate')
