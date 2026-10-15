"""Fail-closed Java-suite routing for explicitly isolated platform-tool PRs."""
import argparse
import json
import os
import re
import subprocess

NEUTRAL = frozenset({'FILE_INDEX.txt', 'docs/architecture/implementation-status.md',
    'docs/operations/stage10-production-deployment-guide-fa.md', 'deploy/postgresql/README.md'})
TOPICS = ('barman_control_plane', 'cnpg_control_plane', 'postgresql_storage',
          'postgresql_local_volume', 'cert_manager_candidate', 'cert_manager_commissioning')
SCRIPT = re.compile(r'scripts/production/(?:build|install|rehearse|render)_('
                    + '|'.join(TOPICS) + r')(?:_bundle)?\.py\Z')
TEST = re.compile(r'scripts/production/tests/test_(' + '|'.join(TOPICS) + r')\.py\Z')
WRAPPERS = frozenset('scripts/production/run-' + name + '.ps1' for name in (
    'barman-control-plane', 'cnpg-control-plane', 'postgresql-storage',
    'postgresql-local-volume', 'cert-manager-commissioning'))


def application_tests(paths):
    # Shared helpers, image recipes/pins, CI, security and unknown paths always run full checks.
    return not paths or len(paths) > 20000 or any(
        p not in NEUTRAL and p not in WRAPPERS and not SCRIPT.fullmatch(p) and not TEST.fullmatch(p)
        for p in paths)


def select(event, base, head, run=subprocess.run):
    full, count = True, None
    if event == 'pull_request' and all(re.fullmatch('[a-f0-9]{40}', s) for s in (base, head)):
        try:
            result = run(['git', 'diff', '--no-ext-diff', '--no-renames', '--name-only', '-z',
                          base + '...' + head, '--'], check=False, capture_output=True, timeout=30)
            if result.returncode == 0 and 0 < len(result.stdout) <= 2 * 1024**2 and result.stdout.endswith(b'\0'):
                paths = result.stdout[:-1].decode('utf-8').split('\0')
                full, count = application_tests(paths), len(paths)
        except (OSError, UnicodeError, subprocess.SubprocessError):
            pass  # Uncertain comparison can never authorize skipping a test.
    return {'application_tests': full, 'reason': 'full-validation' if full else 'isolated-platform-tools',
            'changed_files': count}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--event', required=True)
    p.add_argument('--base', default='')
    p.add_argument('--head', default='')
    a = p.parse_args()
    result = select(a.event, a.base, a.head)
    print(json.dumps(result, sort_keys=True))
    if 'GITHUB_OUTPUT' in os.environ:
        with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as output:
            output.write('application_tests=' + str(result['application_tests']).lower() + '\n')


if __name__ == '__main__': main()
