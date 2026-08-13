# TypeScript and React Coding Standards

This document is the canonical implementation standard for browser/frontend TypeScript and React code. Browser/BFF security and product architecture remain governed by current architecture decisions.

## 1. Tooling baseline

- Prettier is the only formatter.
- ESLint uses flat configuration.
- `typescript-eslint` uses type-aware strict rules for application code.
- TypeScript enables `strict`, `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`, `noImplicitOverride`, `useUnknownInCatchVariables`, and `noFallthroughCasesInSwitch` unless a narrower generated-code exception is documented.
- React official Hooks rules are blocking; disable comments require local explanation and review.
- Formatter/linter/compiler versions are pinned in the frontend lock/toolchain.

## 2. Type safety

- `any` is prohibited in application code except an isolated adapter boundary with validation and a narrow documented suppression.
- External/untrusted data enters as `unknown` and is runtime-validated before trusted use.
- `as` casting is not validation.
- Prefer discriminated unions and exhaustive `never` checks for closed UI/application state.
- Non-null assertions are prohibited unless the invariant is proven immediately at the use site.
- Domain identifiers SHOULD use opaque/branded types at module boundaries where accidental ID mixing is plausible.
- Generated OpenAPI types remain generated transport types and are mapped to feature models when semantics differ.

## 3. React rules

- Components and Hooks are pure; side effects do not run during render.
- Props/state/Hook inputs are treated as immutable.
- Effects synchronize with external systems; derived state is computed rather than mirrored through Effects.
- Server-only modules cannot enter browser bundles.
- Components are named by product/domain intent, not visual implementation detail.
- Error, empty, loading/pending, success, stale/conflict, and permission-denied states are explicit where applicable.
- Accessibility semantics, focus, keyboard behavior, RTL/LTR behavior, and reduced motion are part of component contracts.

## 4. Feature boundaries and imports

- A feature exposes a small explicit public API, normally through `index.ts` or an equivalent repository convention.
- Cross-feature imports into private implementation paths are prohibited.
- Circular feature/module dependencies fail CI.
- Shared UI/platform packages cannot import product features.
- UI primitives contain no product workflow/business rules.
- A reusable primitive enters shared UI only after multiple real consumers and a stable product-neutral API justify extraction.

## 5. State and API boundaries

Preferred state placement:

1. URL for shareable/navigation state;
2. TanStack Query or approved equivalent for server state;
3. form-state library for non-trivial forms;
4. React state for local interaction;
5. global client stores only for bounded cross-route client state with a written reason.

Browser code calls same-origin BFF/public contracts only. It never stores provider/internal access/refresh/ID tokens or server credentials in `localStorage`, `sessionStorage`, IndexedDB, service-worker caches, or client state.

## 6. Forms and mutations

- Client validation improves UX but never replaces server validation.
- Field constraints and server errors map to accessible localized UI states.
- Submission handles pending, duplicate, retry, stale/conflict, and failure behavior deliberately.
- Optimistic updates require rollback/conflict behavior and tests.
- Destructive actions show consequences and require the authentication/authorization/confirmation controls defined by the current threat model.
- Multi-step flows persist only the minimum safe state and define resume/expiry behavior.

## 7. Browser security

- No secrets or trusted authorization decisions exist only in browser code.
- Raw HTML sinks are prohibited unless an approved sanitizer/Trusted Types design and tests exist.
- Redirects and external URLs are allow-listed/validated.
- Private/sensitive responses use `Cache-Control: no-store` where required by the current BFF/security design.
- Service workers MUST NOT cache authenticated private responses, mutations, authentication/session endpoints, or telemetry payloads.
- CSP, frame protection, referrer policy, MIME-sniffing protection, CSRF/origin controls, and cookie policy are verified at the BFF/edge boundary.

## 8. Complexity and maintainability signals

- Component target: <=200 logical lines; larger components trigger decomposition review.
- Hook/function cognitive complexity target: <=15; exceptions require written rationale.
- More than 7 component props triggers API/composition review; it is a design signal, not an automatic object-wrapper rule.
- Wrapper components that add no semantic/accessibility/policy/design value are prohibited.
- Fixed sleeps, fragile CSS/XPath selectors, and implementation-detail browser tests are prohibited when semantic alternatives exist.

## 9. Testing and performance

- Testing Library queries user-visible semantics/roles/names.
- Playwright uses web-first assertions, auto-waiting, isolated test data, and stable role/label/test-id contracts.
- Critical journeys cover keyboard/focus, accessibility, RTL/LTR, responsive viewports, and permission/error states.
- Route bundle growth is governed by an explicit budget; unexplained regression blocks merge.
- Public routes verify status, metadata, initial HTML, caching, Core Web Vitals budget, and indexing behavior where applicable.

## 10. Required CI evidence

Frontend changes run applicable pinned checks for formatter, ESLint, TypeScript typecheck, unit/component tests, accessibility checks, Playwright critical journeys, production build, bundle/performance budgets, dependency/security scanning, and browser-security policy tests.
