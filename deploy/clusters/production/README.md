# Production GitOps root

This is the canonical Argo CD production root selected by ADR-0011. It is intentionally fail-closed before a production environment is commissioned. Environment-specific desired state is added by reviewed promotion PRs only after the production readiness verifier accepts the external evidence bundle. Secret values, unseal material, private keys, tokens, and provider credentials are never committed here.

Application images must be immutable digests and must pass the repository supply-chain gate before promotion. Destructive critical resources require explicit prune confirmation.
