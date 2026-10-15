# PostgreSQL service ownership component

Owner: Platform Reliability. Current authority: ADR-0019/0027/0034/0037/0042.
This is the credential-free ownership component of the **single-server** CNPG
baseline, not a complete cluster installer or production promotion approval.

The four official operator/operand/plugin/sidecar inputs and upstream manifest
checksums are pinned in `infrastructure/production/data/postgresql-images.json`.
`scripts/production/verify_postgresql_artifacts.py` verifies their blocked
candidate scans; `.github/workflows/postgresql-artifacts.yml` runs the exact
four-image gate without credentials or target writes. Go components use
`build_postgresql_candidates.py` and the integrity-pinned
`data/postgresql-source-builds.json`: operator 1.30.1 rebuilds both embedded
architectures, Barman 0.15.1 rebuilds the plugin/sidecar manager, and the static
plugin uses the pinned non-root distroless base rather than UBI. The sidecar keeps
the hash-locked Barman 3.20.1 requirements, rebuilt for Python 3.14.8 on pinned
Alpine 3.24.2. The PostgreSQL 18.6 candidate uses the pinned official Alpine
operand plus exact fixed OpenSSL/zlib packages and a valid UID/GID 26 account.
Both remain blocked until final scan/native compatibility/recovery gates pass. No
source input is treated as an unchanged imported final image. This does not sign,
publish or approve installation. See the Persian Stage10 guide for local usage.

The separate `publish_postgresql_candidates.py` path publishes this complete
source/runtime set only via protected `production-release.yml` on `main`, dispatch
kind `postgresql-candidate`. Fresh registry digest scans precede all signing;
approved package visibility and exact source/runtime SLSA/CycloneDX/signature
verification are mandatory. `publication.json` reports all four images only after
whole-set success, retaining staging/admission/deployment/promotion as
`Not verified`. This does not import the blocked official inputs unchanged or
inherit the older platform packages' public visibility exception: the owner
separately approved public/private visibility only for the four fixed PostgreSQL
infrastructure destinations. Actual visibility is recorded; application images
remain private-only and all integrity/admission gates are unchanged. See the
[Stage10 guide](../../docs/operations/stage10-production-deployment-guide-fa.md)
for dispatch and evidence retrieval; do not supply VPS/Root/custody credentials.

Protected publication `38382418665:1` on `5b0c547e27c5f89d5ec76186defa112739b64f3c`
is complete. The exact four-image set, actual public visibility, publication
receipt hash, recipe and configuration identities are bound in the existing
catalog's `published_security_candidate`. The upstream `components` remain source
inputs, not deployment selections. `verify_published_postgresql.py` verifies this
whole set independently against registry signatures and exact SLSA/CycloneDX
payloads, revalidates the downloaded scan's freshness, and runs network-isolated
native entrypoints and PostgreSQL ownership on the same pulled digests. It does
not rescan images, rebuild, publish or install. A fresh output directory prevents
stale success receipts; any partial failure leaves no `acceptance.json` success.
The Persian guide contains the reproducible invocation. Native smoke alone never
marks CNPG reconciliation, physical recovery, admission or promotion as Passed.

```bash
python3 scripts/production/render_postgresql_ownership.py --candidate
python3 -m unittest discover -s scripts/production/tests -p 'test_postgresql_ownership.py'
python3 scripts/production/test_postgresql_isolation_native.py --disposable
```

The last command creates only a Docker fixture with no network or published ports,
using the existing immutable PostgreSQL 18.4 staging image. It removes only that
container and its anonymous volume. Synthetic trust authentication is never a
production configuration. The test covers all 40 cross-service connection
denials, runtime DDL/role negatives, forced RLS, transaction-local context after
commit/rollback, own-service DML, monitoring isolation, and non-destructive replay.
It does not exercise CNPG reconciliation, real application migrations or recovery.

The renderer emits 10 CNPG 1.30 `DatabaseRole` resources, five `Database` resources
and five immutable ConfigMaps containing public SQL. It makes no network requests
and does not read or generate credentials. Resources target
`platform-data/postgresql`; database/role deletion policy is explicitly `retain`.
The five databases are `authorization`, `conversation`, `identity`, `notification`
and `web_bff`. Compromised Password remains outside mutable PostgreSQL scope.
Business schema changes remain owned by each service's immutable Flyway history.

## Commissioning dependency contract

Credential delivery is a distinct prerequisite: ten exact runtime/migration
identities in `platform-data`, separate from the existing six application
identities. The application ESO controller is not implicitly granted another
namespace. PostgreSQL data Pods remain closed except for ADR-0042's explicitly
owner-approved empty commissioning quarantine described below.

The owner-local 32 GiB filesystem/guard foundation is installed on the current
VPS. `install_postgresql_local_volume.py` prepares only the static 29 GiB LocalPV
reservation (ext4 usable capacity is less than backing size), with Retain,
WaitForFirstConsumer and exact node/first-instance claim binding. It does not
create a PVC, database or Barman component. The Stage10 guide contains its
exact-tree native evidence builder, owner-local invocation and recovery boundary.
The current VPS reservation receipt is verified as `Passed` in the Stage10 guide;
no PVC/database has been created; the separate Barman controller is installed,
but its database sidecar and provider backup are not. Other servers require their own binding
and target receipt.

Do **not** apply this ownership candidate directly to the VPS. The reviewed complete data
bundle must first supply the signed CNPG/operator/database/Barman image evidence,
durable storage guard, finite capacity/pool budgets, TLS and network/mesh isolation,
and the approved backup/restore path. Actual PostgreSQL commissioning is `Not run`.
ParsPack deferral is not off-host backup or production-readiness evidence.

The separate `render_postgresql_quarantine.py` / `install_postgresql_quarantine.py`
path implements only the owner's 2026-10-16 empty commissioning approval. One
signed instance uses the existing guarded 29 GiB Retain reservation and existing
`identity-db-migration` Secret. No five-service ownership reconciliation, Flyway
or business tables are installed. General TCP SQL is denied in `pg_hba`; the only
mesh/network ingress is CNPG's internal control endpoint. The public bundle is
gated by exact-tree signed Ambient/admission/reentry/empty/TLS/denial evidence and
the separate filesystem/guard native suite. Only owner-local sudo can execute it.
Unexpected data, claim ownership or configuration stops with state preserved;
there is no reset, reformat or automatic database rollback. Follow the Persian
living guide; this is not permission to open application traffic or skip recovery.

1. Supply 10 independent, namespace-local `kubernetes.io/basic-auth` Secrets from
   the existing OpenBao authority. Their names are `<service>-db-runtime` and
   `<service>-db-migration`, with `username` equal to the declared PostgreSQL role
   and a distinct `password`. No plaintext belongs in Git, Helm values, argv or
   logs. Existing application Secrets in `platform-apps` are separate projections;
   Kubernetes cannot reference a Secret in another namespace. Existing ESO scoped
   access does **not** authorize new identities in `platform-data` automatically.
   `build_postgresql_secrets_bundle.py` requires exact-tree native proof for a
   separate namespace-scoped ESO controller using the existing signed image.
   `bootstrap_postgresql_secrets_operator.py` runs the owner-supervised delivery
   against existing custody. It creates only missing KV-v2 leaves using CAS=0,
   never rotates existing values, and rejects foreign resources. Ten exact
   read-only auth identities, restricted TokenRequest, shared two-namespace
   API-only ESO validation, source admission, Ambient/STRICT and cross-identity
   denial are verified. Database Pods remain closed. See the Stage10 guide for
   bundle validation, local prompts and safe resumption.
2. Reconcile DatabaseRoles and verify success/password readiness before creating
   Databases; verify each database's expected migration owner. Do not reconcile the
   same role through both `DatabaseRole` and `Cluster.spec.managed.roles`.
3. The supervised complete bootstrap executes each ConfigMap's `isolation.sql`
   against **that database** with the bounded maintenance identity. A ConfigMap
   does not execute SQL. Public/cross-service privileges are revoked, runtime gets
   own-schema DML only, and future migration-owned objects inherit scoped grants.
   Unsafe role attributes/memberships or unexpected ownership stop the transaction
   before grants change; no role adoption, owner rewrite, data deletion or retry
   loop is attempted. Existing runtime sessions require a separate controlled
   cutover: revoking CONNECT does not disconnect established sessions.
4. Run independent destination privilege negatives and service Flyway/RLS tests
   before any application traffic. Restrict `pg_hba`, mesh and NetworkPolicy to
   approved identities and explicit service endpoints; SQL grants alone do not
   establish those controls. Role ceilings/global pool reserve need measured
   complete-stack evidence and are not invented by this ownership renderer.

`cnpg_metrics_exporter`, when present, receives explicit CONNECT, but no application
table grants. PUBLIC function EXECUTE is revoked globally for future functions
created by each migration role, not only per-schema (PostgreSQL defaults require
that scope). Database creation by itself does not revoke PUBLIC CONNECT; the SQL
step and its independent verification are mandatory before opening applications.

Removing the Kubernetes role/database manifest must never drop a production role
or database. Reversible GitOps changes are not a database downgrade. Credential
rotation, PITR and service-specific logical recovery remain separate supervised
operations under the owning runbooks.

Vendor API references: [role management](https://cloudnative-pg.io/docs/1.30/declarative_role_management/),
[database management](https://cloudnative-pg.io/docs/1.30/declarative_database_management/),
[monitoring](https://cloudnative-pg.io/docs/1.30/monitoring/).
