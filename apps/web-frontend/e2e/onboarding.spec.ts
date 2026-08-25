import { test, expect } from '@playwright/test';

test('application shell is reachable', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('main')).toBeVisible();
});

test('registration validates locally and canonicalizes the contact before submission', async ({ page }) => {
  const bodies: unknown[] = [];
  await page.route('**/api/v1/identity/registration', async (route) => {
    bodies.push(route.request().postDataJSON());
    await route.fulfill({ status: 202, contentType: 'application/json', body: '{"accepted":true}' });
  });
  await page.goto('/');

  await page.getByLabel('Email').fill('Person@bücher.example');
  await page.getByLabel('First name').fill(' First ');
  await page.getByLabel('Last name').fill(' Last ');
  await page.getByLabel('Password').fill('short');
  await page.getByRole('button', { name: 'Continue' }).click();
  expect(bodies).toHaveLength(0);

  await page.getByLabel('Password').fill('a-secure-password');
  await page.getByRole('button', { name: 'Continue' }).click();

  await expect.poll(() => bodies.length).toBe(1);
  expect(bodies[0]).toEqual({
    channel: 'EMAIL',
    contact: 'Person@xn--bcher-kva.example',
    password: 'a-secure-password',
    locale: 'fa',
    firstName: 'First',
    lastName: 'Last',
  });
});
