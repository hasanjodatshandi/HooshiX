import { test, expect } from '@playwright/test';

const csrf = 'synthetic-csrf-token-with-at-least-thirty-two-characters';

test('Google login starts only through the BFF and browser receives no provider token', async ({ page }) => {
  const bodies: unknown[] = [];
  await page.route('**/api/v1/auth/session/csrf', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ csrfToken: csrf, mode: 'PREAUTH' }) });
  });
  await page.route('**/api/v1/auth/oidc/google/start', async (route) => {
    bodies.push(route.request().postDataJSON());
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        authorizationUrl: 'http://127.0.0.1:4173/synthetic-google-authorization?state=opaque',
        expiresAt: '2026-08-26T08:10:00Z',
      }),
    });
  });

  await page.goto('/login');
  await page.getByRole('button', { name: 'Continue with Google' }).click();

  await expect(page).toHaveURL(/\/synthetic-google-authorization\?state=opaque$/);
  expect(bodies).toEqual([{ returnTarget: '/oidc/complete' }]);
  expect(JSON.stringify(bodies)).not.toMatch(/token|verifier|nonce|code/i);
});

test('OIDC completion reconstructs only browser session state before navigation', async ({ page }) => {
  await page.route('**/api/v1/auth/session', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ mode: 'AUTHENTICATED_ONBOARDING', authenticated: true, tenantSelected: false }),
    });
  });

  await page.goto('/oidc/complete');

  await expect(page).toHaveURL(/\/profile$/);
  const persisted = await page.evaluate(() => JSON.stringify({
    local: { ...window.localStorage },
    session: { ...window.sessionStorage },
  }));
  expect(persisted).not.toMatch(/authenticated|tenantSelected|token|verifier|nonce|authorizationUrl/i);
});

test('Google unlink accepts a no-content response and keeps provider data transient', async ({ page }) => {
  await page.route('**/api/v1/auth/session', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: '{"mode":"AUTHENTICATED_ONBOARDING","authenticated":true,"tenantSelected":false}' });
  });
  await page.route('**/api/v1/identity/external-identities', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: '{"googleLinked":true}' });
  });
  await page.route('**/api/v1/auth/session/csrf', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ csrfToken: csrf, mode: 'AUTHENTICATED_ONBOARDING' }) });
  });
  await page.route('**/api/v1/identity/external-identities/google', async (route) => {
    await route.fulfill({ status: 204, body: '' });
  });

  await page.goto('/security/external-identities');
  await page.getByRole('button', { name: 'Unlink Google' }).click();

  await expect(page.getByRole('status')).toHaveText('Google is not linked.');
});
