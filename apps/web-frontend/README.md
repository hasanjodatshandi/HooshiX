# HooshiX Web Frontend

## Scope

This frontend contains the completed roadmap foundation plus onboarding, profile/contact, and password-lifecycle repository slices.

## Boundary rules

- Browser code communicates only with Web BFF.
- Browser code does not call Identity, Authorization, Notification, or other internal services.
- API models follow the generated Web BFF OpenAPI schema.
- Session authority remains server-side.
- Every BFF request uses the single finite abortable client boundary; loaders abort on unmount.
- Browser persistence never stores authentication or Tenant-selection state. Only the canonical
  registration contact may live temporarily in tab-scoped `sessionStorage` until verification.

## API generation

The BFF contract authority is `services/web-bff/contracts/openapi.yaml`.

Run the pinned frontend toolchain command:

```bash
npm run generate:api
npm run check:api
npm run test:components
npm run build
npm run test:a11y
npm run e2e
```

`check:api` regenerates the complete public BFF transport types into a temporary directory and
fails when the committed output differs. Application API request and response types are derived
from that generated schema rather than duplicated by hand.

## Current state

Implemented:

- React/TypeScript baseline
- BFF client boundary
- registration API slice
- OpenAPI generation entry point
- login/session and Tenant-selection flows
- profile/contact flows
- password recovery/change flows with server-only refresh credential custody
- bounded BFF timeout/cancellation and validated safe RFC Problem mapping
- server-session restoration without persisted browser authentication authority
- duplicate-submit/busy handling, prompt credential/proof clearing, and storage-failure recovery
- application error boundary that exposes no caught error detail
- typed English/Persian catalogs consumed by every current journey, with browser-preference
  initialization and explicit RTL/LTR switching without persistent client state
- route-heading focus management and native keyboard semantics
- Vitest/React Testing Library component coverage for localization, focus, and safe error recovery
- an executable Chromium/axe WCAG A/AA accessibility gate
- twenty-seven Playwright journeys across accessibility, localization, keyboard/focus,
  privacy/resilience, onboarding, and authenticated account/Tenant behavior
- exact reviewed npm versions with React/ReactDOM 19.2.7 runtime alignment
- pinned OSV-Scanner lockfile advisory and immutable-image Semgrep JS/TS policy gates
- a digest-pinned Caddy runtime image that runs as UID/GID 10001 with a read-only filesystem,
  no Linux capabilities, a separate health port, and SPA-route fallback
- inclusion in the six-component Production Syft/Grype/Cosign/Kyverno release-evidence contract

Remaining broader work:

- risk-based coverage thresholds and specialized security mutation evidence in the later
  performance/reliability hardening stage
- deployed staging/production browser journey evidence

Repository image and release-path verification is not deployment evidence. Production frontend
routing, workload manifests, environment configuration, and executed signing/admission remain
outside this slice and are `NOT VERIFIED`.
