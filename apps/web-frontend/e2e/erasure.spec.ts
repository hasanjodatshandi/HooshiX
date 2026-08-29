import { test, expect } from '@playwright/test';

const csrf = 'synthetic-csrf-token-with-at-least-thirty-two-characters';

test('account erasure requires explicit confirmation and MFA then clears browser state', async ({ page }) => {
  await page.route('**/api/v1/auth/session', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: '{"mode":"AUTHENTICATED_ONBOARDING","authenticated":true,"tenantSelected":false}',
    });
  });
  await page.route('**/api/v1/identity/mfa', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: '{"totpEnabled":true,"recoveryCodesRemaining":8}' });
  });
  await page.route('**/api/v1/auth/session/csrf', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ csrfToken: csrf, mode: 'AUTHENTICATED_ONBOARDING' }) });
  });
  let submitted: unknown;
  await page.route('**/api/v1/identity/erasure', async (route) => {
    submitted = route.request().postDataJSON();
    await route.fulfill({
      status: 202,
      contentType: 'application/json',
      body: JSON.stringify({
        erasureRequestId: '11111111-1111-4111-8111-111111111111',
        state: 'REQUESTED',
        participantPolicyVersion: '1',
      }),
    });
  });

  await page.goto('/security/account-erasure');
  const submit = page.getByRole('button', { name: 'Permanently erase my account' });
  await expect(submit).toBeDisabled();
  await page.getByLabel('Type ERASE_MY_ACCOUNT to confirm').fill('ERASE_MY_ACCOUNT');
  await page.getByLabel('Current MFA proof', { exact: true }).fill('123456');
  await submit.click();

  await expect(page).toHaveURL(/\/login$/);
  expect(submitted).toEqual({
    confirmation: 'ERASE_MY_ACCOUNT',
    mfaProof: { type: 'TOTP', code: '123456' },
  });
  const persisted = await page.evaluate(() => ({
    local: window.localStorage.getItem('hooshix.frontend.state'),
    session: window.sessionStorage.getItem('hooshix.frontend.registration'),
  }));
  expect(persisted).toEqual({ local: null, session: null });
});
