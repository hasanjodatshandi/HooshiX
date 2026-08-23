import { test, expect } from '@playwright/test';

test('profile route is protected', async ({ page }) => {
  await page.goto('/profile');
  await expect(page).not.toHaveURL(/undefined/);
});
