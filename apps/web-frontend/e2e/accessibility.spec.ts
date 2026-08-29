import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';

async function expectNoAccessibilityViolations(page: Page) {
  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
    .analyze();
  expect(results.violations).toEqual([]);
}

test('@a11y registration is accessible in English and Persian', async ({ page }) => {
  await page.goto('/');
  await expectNoAccessibilityViolations(page);

  await page.getByLabel('Language').selectOption('fa');
  await expect(page.locator('html')).toHaveAttribute('lang', 'fa');
  await expect(page.locator('html')).toHaveAttribute('dir', 'rtl');
  await expectNoAccessibilityViolations(page);
});

test('@a11y login is accessible', async ({ page }) => {
  await page.goto('/login');
  await expectNoAccessibilityViolations(page);
});

test('@a11y authenticated account and tenant routes are accessible', async ({ page }) => {
  await page.route('**/api/v1/auth/session', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: '{"mode":"TENANT_AUTHENTICATED","authenticated":true,"tenantSelected":true}',
    });
  });
  await page.route('**/api/v1/identity/profile', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: '{"id":"11111111-1111-4111-8111-111111111111","firstName":"Sample","lastName":"Person"}',
    });
  });
  await page.route('**/api/v1/identity/contacts', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
  });
  await page.route('**/api/v1/identity/tenants', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: '{"tenants":[]}' });
  });
  await page.route('**/api/v1/identity/tenants/invitations/received', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: '{"invitations":[]}' });
  });
  await page.route('**/api/v1/identity/mfa', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: '{"totpEnabled":false,"recoveryCodesRemaining":0}' });
  });
  await page.route('**/api/v1/identity/external-identities', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: '{"googleLinked":false}' });
  });

  for (const path of [
    '/application',
    '/profile',
    '/password/change',
    '/security/mfa',
    '/security/external-identities',
    '/security/account-erasure',
    '/tenant-select',
    '/tenants/manage',
  ]) {
    await page.goto(path);
    await expect(page.locator('main')).toBeVisible();
    await expectNoAccessibilityViolations(page);
  }
});
