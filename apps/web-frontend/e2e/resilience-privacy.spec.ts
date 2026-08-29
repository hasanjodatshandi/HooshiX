import { expect, test } from '@playwright/test';

const csrf = 'synthetic-csrf-token-with-at-least-thirty-two-characters';

test('BFF failures expose only a stable safe code and clear submitted credentials', async ({ page }) => {
  await page.route('**/api/v1/auth/session/csrf', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ csrfToken: csrf, mode: 'PREAUTH' }),
    });
  });
  await page.route('**/api/v1/auth/local', async (route) => {
    await route.fulfill({
      status: 503,
      contentType: 'application/problem+json',
      body: JSON.stringify({
        type: 'https://errors.hooshix.test/dependency-unavailable',
        title: 'Dependency unavailable',
        status: 503,
        code: 'AUTH_DEPENDENCY_UNAVAILABLE',
        detail: 'provider-token=must-never-reach-the-browser',
      }),
    });
  });

  await page.goto('/login');
  await page.getByLabel('Email').fill('person@example.com');
  await page.getByLabel('Password').fill('current password');
  await page.getByRole('button', { name: 'Continue', exact: true }).click();

  await expect(page.getByRole('alert')).toHaveText('AUTH_DEPENDENCY_UNAVAILABLE');
  await expect(page.getByLabel('Password')).toHaveValue('');
  await expect(page.getByText(/provider-token/)).toHaveCount(0);
  const persisted = await page.evaluate(() => JSON.stringify({
    local: { ...window.localStorage },
    session: { ...window.sessionStorage },
  }));
  expect(persisted).not.toMatch(/current password|provider-token|authenticated|tenantSelected/i);
});

test('BFF request timeout is finite and duplicate submit is suppressed', async ({ page }) => {
  let loginRequests = 0;
  await page.route('**/api/v1/auth/session/csrf', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ csrfToken: csrf, mode: 'PREAUTH' }),
    });
  });
  await page.route('**/api/v1/auth/local', async (route) => {
    loginRequests++;
    await new Promise((resolve) => setTimeout(resolve, 5_000));
    await route.fulfill({ status: 504, contentType: 'application/problem+json', body: '{}' });
  });

  await page.goto('/login');
  await page.getByLabel('Email').fill('person@example.com');
  await page.getByLabel('Password').fill('current password');
  const submit = page.getByRole('button', { name: 'Continue', exact: true });
  await submit.dblclick();

  await expect(page.getByRole('alert')).toHaveText('BFF_REQUEST_TIMEOUT', { timeout: 4_500 });
  expect(loginRequests).toBe(1);
});

test('disabled browser storage does not crash registration', async ({ page }) => {
  await page.addInitScript(() => {
    for (const name of ['localStorage', 'sessionStorage'] as const) {
      Object.defineProperty(window, name, {
        configurable: true,
        get() { throw new DOMException('Storage disabled', 'SecurityError'); },
      });
    }
  });
  await page.route('**/api/v1/identity/registration', async (route) => {
    await route.fulfill({ status: 202, contentType: 'application/json', body: '{"accepted":true}' });
  });

  await page.goto('/');
  await page.getByLabel('Email').fill('person@example.com');
  await page.getByLabel('First name').fill('First');
  await page.getByLabel('Last name').fill('Last');
  await page.getByLabel('Password').fill('a-secure-password');
  await page.getByRole('button', { name: 'Continue' }).click();

  await expect(page).toHaveURL(/\/verify$/);
  await expect(page.getByRole('heading', { name: 'Verification' })).toBeVisible();
});

test('registration contact is tab-scoped and cleared when the flow is abandoned', async ({ page }) => {
  await page.route('**/api/v1/identity/registration', async (route) => {
    await route.fulfill({ status: 202, contentType: 'application/json', body: '{"accepted":true}' });
  });

  await page.goto('/');
  await page.getByLabel('Email').fill('person@example.com');
  await page.getByLabel('First name').fill('First');
  await page.getByLabel('Last name').fill('Last');
  await page.getByLabel('Password').fill('a-secure-password');
  await page.getByRole('button', { name: 'Continue' }).click();

  await expect(page).toHaveURL(/\/verify$/);
  await expect.poll(() => page.evaluate(() => window.sessionStorage.getItem('hooshix.frontend.registration')?.includes('person@example.com') ?? false)).toBe(true);
  await page.evaluate(() => {
    window.history.pushState({}, '', '/login');
    window.dispatchEvent(new PopStateEvent('popstate'));
  });
  await expect(page.getByRole('heading', { name: 'Login' })).toBeVisible();
  await expect.poll(() => page.evaluate(() => window.sessionStorage.getItem('hooshix.frontend.registration'))).toBeNull();
});

test('unmount aborts an in-flight loader without a late error', async ({ page }) => {
  await page.route('**/api/v1/auth/session', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: '{"mode":"AUTHENTICATED_ONBOARDING","authenticated":true,"tenantSelected":false}',
    });
  });
  await page.route('**/api/v1/identity/profile', async (route) => {
    await new Promise((resolve) => setTimeout(resolve, 1_000));
    await route.fulfill({ status: 500, contentType: 'application/problem+json', body: '{}' });
  });
  await page.route('**/api/v1/identity/contacts', async (route) => {
    await new Promise((resolve) => setTimeout(resolve, 1_000));
    await route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
  });

  await page.goto('/profile');
  await page.evaluate(() => {
    window.history.pushState({}, '', '/application');
    window.dispatchEvent(new PopStateEvent('popstate'));
  });

  await expect(page.getByRole('heading', { name: 'Application' })).toBeVisible();
  await page.waitForTimeout(1_200);
  await expect(page.getByRole('alert')).toHaveCount(0);
});
