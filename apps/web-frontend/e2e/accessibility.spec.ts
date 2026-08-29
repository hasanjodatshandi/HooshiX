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

test('@a11y authenticated application navigation is accessible', async ({ page }) => {
  await page.route('**/api/v1/auth/session', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: '{"mode":"TENANT_AUTHENTICATED","authenticated":true,"tenantSelected":true}',
    });
  });
  await page.goto('/application');
  await expect(page.getByRole('heading', { name: 'Application' })).toBeVisible();
  await expectNoAccessibilityViolations(page);
});
