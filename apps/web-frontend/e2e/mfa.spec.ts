import { test, expect } from '@playwright/test';

const csrf = 'synthetic-csrf-token-with-at-least-thirty-two-characters';

test('password login requiring MFA exposes no server challenge and completes with a TOTP proof', async ({ page }) => {
  const requests: Array<{ path: string; body: unknown }> = [];
  let csrfRotations = 0;
  await page.route('**/api/v1/auth/session/csrf', async (route) => {
    csrfRotations++;
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ csrfToken: `${csrf}-${csrfRotations}`, mode: csrfRotations === 1 ? 'PREAUTH' : 'MFA_PREAUTH' }) });
  });
  await page.route('**/api/v1/auth/local', async (route) => {
    requests.push({ path: '/local', body: route.request().postDataJSON() });
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ csrfToken: csrf, mode: 'MFA_PREAUTH' }) });
  });
  await page.route('**/api/v1/auth/mfa/complete', async (route) => {
    requests.push({ path: '/mfa/complete', body: route.request().postDataJSON() });
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ csrfToken: `${csrf}-rotated`, mode: 'AUTHENTICATED_ONBOARDING' }) });
  });

  await page.goto('/login');
  await page.getByLabel('Email').fill('person@example.com');
  await page.getByLabel('Password').fill('current password');
  await page.getByRole('button', { name: 'Continue', exact: true }).click();
  await expect(page).toHaveURL(/\/login\/mfa$/);
  await page.reload();
  await page.getByLabel('Six-digit code').fill('123456');
  await page.getByRole('button', { name: 'Verify' }).click();

  await expect(page).toHaveURL(/\/tenant-select$/);
  expect(requests).toEqual([
    { path: '/local', body: { channel: 'EMAIL', contact: 'person@example.com', password: 'current password' } },
    { path: '/mfa/complete', body: { type: 'TOTP', code: '123456' } },
  ]);
  expect(JSON.stringify(requests)).not.toContain('challenge');
  expect(csrfRotations).toBe(2);
});

test('TOTP enrollment displays its secret and ten recovery codes only in transient page state', async ({ page }) => {
  await page.route('**/api/v1/auth/session', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: '{"mode":"AUTHENTICATED_ONBOARDING","authenticated":true,"tenantSelected":false}',
    });
  });
  await page.route('**/api/v1/identity/mfa', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: '{"totpEnabled":false,"recoveryCodesRemaining":0}' });
  });
  await page.route('**/api/v1/auth/session/csrf', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ csrfToken: csrf, mode: 'AUTHENTICATED_ONBOARDING' }) });
  });
  await page.route('**/api/v1/identity/mfa/totp/enrollment', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        enrollmentChallenge: 'abcdefghijklmnopqrstuvwxyzABCDEFGH123456789',
        base32Secret: 'A'.repeat(52),
        otpauthUri: `otpauth://totp/SajTech%3Aperson?secret=${'A'.repeat(52)}&issuer=SajTech&algorithm=SHA256&digits=6&period=30`,
        expiresAt: '2026-01-01T00:10:00Z',
      }),
    });
  });
  const recoveryCodes = [...'ABCDEFGHJK'].map((suffix) => `AAAA-BBBB-CCCC-DDD${suffix}`);
  await page.route('**/api/v1/identity/mfa/totp/enrollment/confirm', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ recoveryCodes }) });
  });

  await page.goto('/security/mfa');
  await page.getByRole('button', { name: 'Set up authenticator' }).click();
  await expect(page.getByLabel('Authenticator secret')).toHaveText('A'.repeat(52));
  await page.getByLabel('Six-digit code').fill('123456');
  await page.getByRole('button', { name: 'Confirm authenticator' }).click();

  await expect(page.getByRole('heading', { name: 'Save these recovery codes now' })).toBeVisible();
  await expect(page.locator('ol li')).toHaveCount(10);
  const persisted = await page.evaluate(() => JSON.stringify({
    local: { ...window.localStorage },
    session: { ...window.sessionStorage },
  }));
  expect(persisted).not.toContain('base32Secret');
  expect(persisted).not.toContain('recoveryCodes');
  await page.getByRole('button', { name: 'I have saved them' }).click();
  await expect(page.locator('ol li')).toHaveCount(0);
});

test('TOTP disable accepts the documented empty success response and clears the proof', async ({ page }) => {
  await page.route('**/api/v1/auth/session', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: '{"mode":"AUTHENTICATED_ONBOARDING","authenticated":true,"tenantSelected":false}' });
  });
  await page.route('**/api/v1/identity/mfa', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: '{"totpEnabled":true,"recoveryCodesRemaining":8}' });
  });
  await page.route('**/api/v1/auth/session/csrf', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ csrfToken: csrf, mode: 'AUTHENTICATED_ONBOARDING' }) });
  });
  await page.route('**/api/v1/identity/mfa/totp', async (route) => {
    await route.fulfill({ status: 200, body: '' });
  });

  await page.goto('/security/mfa');
  await page.getByLabel('Current proof', { exact: true }).fill('123456');
  await page.getByRole('button', { name: 'Disable two-factor authentication' }).click();

  await expect(page.getByRole('status')).toContainText('TOTP is disabled');
  await expect(page.getByLabel('Current proof', { exact: true })).toHaveCount(0);
});
