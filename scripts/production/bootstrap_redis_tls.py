"""Supervised first Redis leaf; existing encrypted intermediate and OpenBao, no CA replacement."""
import hashlib
import bootstrap_redis_secrets as previous
import install_redis_tls as installer
import redis_tls_material as material


def commission(request, revision):
    request = request.copy()
    password = request.pop('intermediate_passphrase', None)
    material.require(isinstance(password, str) and 20 <= len(password) <= 128
        and not any(ord(c) < 32 or ord(c) == 127 for c in password), 'REDIS_TLS_ISSUER_PASSPHRASE_REJECTED')

    def execute(plan, **kwargs):
        def delivery(plan, **options):
            for package in ('openssl', 'libssl3t64:amd64'):
                material.require(material.platform.native(['/usr/bin/dpkg-query', '-W', '-f=${Version}', package])
                    .decode() == material.custody.PACKAGE, 'REDIS_TLS_HOST_CRYPTO_VERSION_REVIEW_REQUIRED')
            issuer, key = material.existing_issuer(options['kube'], options['ca'])
            encrypted = hashlib.sha256(key.read_bytes()).hexdigest()
            result = installer.execute(plan, **options, issuer=issuer,
                issue=lambda: material.issue(options['ca'], issuer, key, password.encode()))
            material.require(material.existing_issuer(options['kube'], options['ca']) == (issuer, key)
                and hashlib.sha256(key.read_bytes()).hexdigest() == encrypted, 'REDIS_TLS_ISSUER_CHANGED')
            return result
        return previous.execute(plan, delivery=delivery, **kwargs)

    return previous.pg.commission(request, revision, execute=execute, token_requester=previous.token_request,
                                  audit_label='Redis', preserve=previous.preserved)


def main(revision):
    return previous.pg.main(revision, commissioner=commission)
