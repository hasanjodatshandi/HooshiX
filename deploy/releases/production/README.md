# Production release manifests

A production release manifest is public metadata only. It is added through a reviewed pull request under this directory and must pass `scripts/production/verify_release.py` before the protected `Production release evidence` workflow can run on `main`.

The manifest contains exact image digests for all six current application release components (the
five Java services plus `web-frontend`), the exact protected Cosign workflow identity, measured
capacity references, production HIBP artifact identity and bounds, external evidence identifiers,
and Kubernetes Secret object names. It MUST NOT contain passwords, tokens, private keys, provider
credentials, secret values, WireGuard private keys, or OpenBao unseal material.

After the release workflow produces verified Syft/Grype/Cosign evidence for the exact digests, use
`scripts/production/render_gitops.py` with the same manifest to generate the five current service
manifests and the six-component release-admission policy. Commit generated manifests through a
separate reviewed production-promotion pull request. The current renderer does not create a
`web-frontend` workload or route; Production frontend deployment and routing remain a later,
separately reviewed commissioning concern. Production does not rebuild images after staging
validation.
