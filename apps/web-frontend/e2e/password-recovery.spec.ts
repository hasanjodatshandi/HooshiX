import { test, expect } from '@playwright/test';

test('recovery requests and confirms an eight-digit purpose-bound challenge', async ({ page }) => {
  const bodies: unknown[] = [];
  await page.route('**/api/v1/password/recovery/**', async (route) => {
    bodies.push(route.request().postDataJSON());
    await route.fulfill({ status: 200, contentType: 'application/json', body: '{"accepted":true}' });
  });
  await page.goto('/password/recovery');

  await page.getByLabel('Email').fill('person@example.com');
  await page.getByRole('button', { name: 'Send recovery code' }).click();
  await page.getByLabel('Eight-digit code').fill('12345678');
  await page.getByLabel('New password').fill('a-secure-next-password');
  await page.getByRole('button', { name: 'Reset password' }).click();

  await expect(page.getByRole('status')).toHaveText('Password reset complete');
  expect(bodies).toEqual([
    { channel: 'EMAIL', contact: 'person@example.com' },
    {
      channel: 'EMAIL',
      contact: 'person@example.com',
      code: '12345678',
      newPassword: 'a-secure-next-password',
    },
  ]);
});

test('authenticated change never exposes a refresh credential to browser code', async ({ page }) => {
  await page.route('**/api/v1/auth/session', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: '{"mode":"AUTHENTICATED_ONBOARDING","authenticated":true,"tenantSelected":false}',
    });
  });
  let body: Record<string, unknown> | undefined;
  await page.route('**/api/v1/auth/session/csrf', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: '{"csrfToken":"recovered-csrf-token-with-more-than-32-characters","mode":"AUTHENTICATED_ONBOARDING"}',
    });
  });
  await page.route('**/api/v1/password/change', async (route) => {
    body = route.request().postDataJSON();
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: '{"changed":true,"csrfToken":"rotated-csrf-token-with-more-than-32-characters"}',
    });
  });
  await page.goto('/password/change');

  await page.getByLabel('Current password').fill('current password');
  await page.getByLabel('New password').fill('a-secure-next-password');
  await page.getByRole('button', { name: 'Save password' }).click();

  await expect(page.getByRole('status')).toHaveText('Password changed');
  expect(body).toEqual({
    currentPassword: 'current password',
    newPassword: 'a-secure-next-password',
  });
  expect(body).not.toHaveProperty('refreshCredential');
});
