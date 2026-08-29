import { expect, test } from '@playwright/test';

test('locale selection drives translated UI, direction, and registration contract', async ({ page }) => {
  let body: Record<string, unknown> | undefined;
  await page.route('**/api/v1/identity/registration', async (route) => {
    body = route.request().postDataJSON() as Record<string, unknown>;
    await route.fulfill({ status: 202, contentType: 'application/json', body: '{"accepted":true}' });
  });
  await page.goto('/');
  await page.getByLabel('Language').selectOption('fa');
  await expect(page.locator('html')).toHaveAttribute('dir', 'rtl');
  await expect(page.getByRole('heading', { name: 'ثبت‌نام' })).toBeVisible();

  await page.getByLabel('ایمیل').fill('person@example.com');
  await page.getByLabel('نام', { exact: true }).fill('نام');
  await page.getByLabel('نام خانوادگی').fill('خانوادگی');
  await page.getByLabel('رمز عبور').fill('a-secure-password');
  await page.getByRole('button', { name: 'ادامه' }).click();

  await expect.poll(() => body?.locale).toBe('fa');
});

test('keyboard navigation and client-side route focus remain semantic', async ({ page }) => {
  await page.route('**/api/v1/auth/session', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: '{"mode":"TENANT_AUTHENTICATED","authenticated":true,"tenantSelected":true}',
    });
  });
  await page.route('**/api/v1/identity/tenants/invitations/received', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: '{"invitations":[]}' });
  });
  await page.route('**/api/v1/identity/tenants/invitations', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: '{"invitations":[]}' });
  });

  await page.goto('/application');
  const link = page.getByRole('link', { name: 'Tenant management' });
  await link.focus();
  await page.keyboard.press('Enter');

  await expect(page.getByRole('heading', { name: 'Tenant management', level: 1 })).toBeFocused();
});
