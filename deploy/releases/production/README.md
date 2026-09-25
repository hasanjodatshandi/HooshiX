# Production release manifests

A production release manifest is public metadata only. It is added through a reviewed pull request under this directory and must pass `scripts/production/verify_release.py` before the protected `Production release evidence` workflow can run on `main`.

The version-2 manifest contains exact image digests for all seven current application release components (the
six Java services plus `web-frontend`), the exact protected Cosign workflow identity, measured
capacity references, production HIBP artifact identity and bounds, external evidence identifiers,
and Kubernetes Secret object names. It MUST NOT contain passwords, tokens, private keys, provider
credentials, secret values, WireGuard private keys, or OpenBao unseal material.

After the release workflow produces verified Syft/Grype/Cosign evidence for the exact digests, use
`scripts/production/render_gitops.py` with the same manifest to generate all six service manifests,
the hardened static `web-frontend` workload, and the seven-component release-admission policy.
Commit generated manifests through a separate reviewed production-promotion pull request. The
reviewed edge desired state routes `/api` and `/api/*` only to BFF and non-API traffic only to the frontend Service.
The frontend route fails closed if its Service is unavailable. Production does not rebuild images
after staging validation.
