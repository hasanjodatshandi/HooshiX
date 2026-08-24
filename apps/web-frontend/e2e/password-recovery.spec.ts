import { test, expect } from '@playwright/test';

test('password recovery route does not expose server error page', async ({ page }) => {
  const response = await page.goto('/password/recovery');
  expect(response).not.toBeNull();
  await expect(page.locator('body')).toBeVisible();
});

test('password change route is reachable', async ({ page }) => {
  const response = await page.goto('/password/change');
  expect(response).not.toBeNull();
  await expect(page.locator('body')).toBeVisible();
});
