import { test, expect } from '@playwright/test';

test('application shell is reachable', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('main')).toBeVisible();
});
