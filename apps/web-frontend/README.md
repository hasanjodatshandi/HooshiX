# HooshiX Web Frontend

## Scope

This frontend contains the completed roadmap foundation plus onboarding, profile/contact, and password-lifecycle repository slices.

## Boundary rules

- Browser code communicates only with Web BFF.
- Browser code does not call Identity, Authorization, Notification, or other internal services.
- API models follow the generated Web BFF OpenAPI schema.
- Session authority remains server-side.

## API generation

The BFF contract authority is `services/web-bff/contracts/openapi.yaml`.

Run the pinned frontend toolchain command:

```bash
npm run generate:api
npm run check:api
npm run build
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
- critical Playwright coverage

Remaining broader work:

- accessibility and localization completion
- deployed staging/production browser journey evidence
