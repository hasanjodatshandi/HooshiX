# HooshiX Web Frontend

## Scope

This is the Milestone 3 frontend foundation.

## Boundary rules

- Browser code communicates only with Web BFF.
- Browser code does not call Identity, Authorization, Notification, or other internal services.
- API models follow .
- Session authority remains server-side.

## API generation

The BFF contract is the authority:



Run the pinned frontend toolchain command:



## Current state

Implemented:

- React/TypeScript baseline
- BFF client boundary
- registration API slice
- OpenAPI generation entry point

Not completed yet:

- generated client CI verification
- login/session UI
- tenant selection
- Playwright journey
- accessibility and localization completion
