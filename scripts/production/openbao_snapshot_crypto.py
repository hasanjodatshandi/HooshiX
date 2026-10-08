"""Bounded PGP snapshot envelopes; only public recipients reach the VPS."""
from __future__ import annotations

import base64
import os
import resource
import select
import signal
import subprocess
import tempfile
import time
from pathlib import Path

import activate_openbao_host as host

MAX_SNAPSHOT = 32 * 1024 * 1024
MAX_CIPHERTEXT = MAX_SNAPSHOT + 1024 * 1024
ENV = {'PATH': '/usr/bin:/bin', 'LC_ALL': 'C'}


def run(home, args, data=b'', *, password=None, bound=MAX_CIPHERTEXT):
    """Bounded in-memory output; credentials use a pipe, never argv/env."""
    argv = ['/usr/bin/gpg', '--no-options', '--homedir', str(home), '--batch', '--no-tty',
            '--pinentry-mode', 'loopback', *args]
    descriptor, process = None, None
    try:
        if password is not None:
            host.require(isinstance(password, str) and 20 <= len(password) <= 128
                         and not any(c in password for c in '\r\n\x00'), 'SNAPSHOT_PASSPHRASE_REJECTED')
            descriptor, writer = os.pipe()
            try:
                os.write(writer, password.encode() + b'\n')
            finally:
                os.close(writer)
            argv[1:1] = ['--passphrase-fd', str(descriptor)]
        # Input is already ciphertext (PGP, Raft barrier, or protected private export).
        # In particular decrypted output NEVER goes to a disk-backed stdout file.
        with tempfile.TemporaryFile() as source:
            source.write(data)
            source.seek(0)
            def limits():
                resource.setrlimit(resource.RLIMIT_FSIZE, (bound, bound))
                resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
            process = subprocess.Popen(argv, stdin=source, stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL, env=ENV, start_new_session=True, preexec_fn=limits,
                pass_fds=(descriptor,) if descriptor is not None else ())
            deadline, output = time.monotonic() + 90, bytearray()
            while True:
                remaining = deadline - time.monotonic()
                ready, _, _ = select.select([process.stdout], [], [], max(0, remaining))
                host.require(remaining > 0 and ready, 'SNAPSHOT_CRYPTO_TIMEOUT')
                chunk = os.read(process.stdout.fileno(), min(65536, bound + 1 - len(output)))
                if not chunk:
                    break
                output.extend(chunk)
                host.require(len(output) <= bound, 'SNAPSHOT_CRYPTO_OUTPUT_BOUND')
            process.wait(timeout=max(0.1, deadline - time.monotonic()))
            host.require(process.returncode == 0, 'SNAPSHOT_CRYPTO_FAILED')
            return bytes(output)
    except (OSError, subprocess.SubprocessError):
        raise host.custody.BootstrapFailed('SNAPSHOT_CRYPTO_FAILED') from None
    finally:
        if process is not None and process.poll() is None:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=5)
        if process is not None:
            process.stdout.close()
        if descriptor is not None:
            os.close(descriptor)


def stop_agent(home):
    # No persistent keyring. Complete agent teardown before temporary directory cleanup.
    try:
        result = subprocess.run(['/usr/bin/gpgconf', '--homedir', str(home), '--kill', 'gpg-agent'],
            stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            timeout=5, check=False, env=ENV)
        host.require(result.returncode == 0, 'SNAPSHOT_AGENT_TEARDOWN_FAILED')
    except (OSError, subprocess.SubprocessError):
        raise host.custody.BootstrapFailed('SNAPSHOT_AGENT_TEARDOWN_FAILED') from None


def validate_ciphertext(value):
    host.require(isinstance(value, bytes) and 192 <= len(value) <= MAX_CIPHERTEXT,
                 'SNAPSHOT_CIPHERTEXT_REQUIRED')
    tag = value[0] & 63 if value[0] & 64 else (value[0] >> 2) & 15
    host.require(value[0] & 128 and tag == 1, 'SNAPSHOT_CIPHERTEXT_REQUIRED')


def seal(snapshot, recipients):
    host.require(isinstance(snapshot, bytes) and 1 <= len(snapshot) <= MAX_SNAPSHOT,
                 'SNAPSHOT_SIZE_BOUND')
    host.public_keys(recipients)
    with tempfile.TemporaryDirectory(prefix='hooshix-snapshot-pub-') as temporary:
        home = Path(temporary)
        home.chmod(0o700)
        try:
            for recipient in recipients:
                run(home, ['--import'], base64.b64decode(recipient, validate=True), bound=32768)
            listing = run(home, ['--with-colons', '--list-keys'], bound=32768)
            fingerprints = [line.split(b':')[9].decode('ascii') for line in listing.splitlines()
                            if line.startswith(b'fpr:')]
            host.require(len(fingerprints) == 3 and len(set(fingerprints)) == 3,
                         'SNAPSHOT_PUBLIC_RECIPIENT_MISMATCH')
            args = ['--trust-model', 'always', '--compress-algo', 'none', '--cipher-algo', 'AES256']
            for fingerprint in fingerprints:
                args += ['--recipient', fingerprint]
            encrypted = run(home, [*args, '--encrypt'], snapshot)
            validate_ciphertext(encrypted)
            return encrypted
        finally:
            stop_agent(home)


def recover(encrypted, secret_export, password):
    """Owner process only; never writes decrypted snapshot or shares to disk."""
    validate_ciphertext(encrypted)
    host.require(isinstance(secret_export, bytes) and 0 < len(secret_export) <= 32768,
                 'SNAPSHOT_PRIVATE_EXPORT_REJECTED')
    with tempfile.TemporaryDirectory(prefix='hooshix-snapshot-proof-') as temporary:
        home = Path(temporary)
        home.chmod(0o700)
        try:
            run(home, ['--import'], secret_export, bound=32768)
            return run(home, ['--decrypt'], encrypted, password=password, bound=MAX_SNAPSHOT)
        finally:
            stop_agent(home)
