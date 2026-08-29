# Accessibility verification

Accessibility is an executable frontend gate, not a manual milestone note.

`npm run test:a11y` runs `@axe-core/playwright` in Chromium against:

- registration in English/LTR and Persian/RTL;
- login;
- all current authenticated account and Tenant route shells (application, profile,
  password change, MFA, external identity, erasure, Tenant selection and management).

The scan enforces WCAG 2.0 A/AA and WCAG 2.1 A/AA rule tags. It complements,
but does not replace, semantic RTL component tests and Playwright keyboard/focus
journeys. The CI workflow runs component tests, the accessibility gate, and the
remaining browser journeys as distinct required steps.
