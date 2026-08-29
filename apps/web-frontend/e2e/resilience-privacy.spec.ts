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

test('late session restoration cannot overwrite a completed login', async ({ page }) => {
  await page.route('**/api/v1/auth/session', async (route) => {
    await new Promise((resolve) => setTimeout(resolve, 500));
    await route.fulfill({
      status: 401,
      contentType: 'application/problem+json',
      body: JSON.stringify({
        type: 'about:blank',
        title: 'Invalid session',
        status: 401,
        code: 'INVALID_SESSION',
      }),
    });
  });
  await page.route('**/api/v1/auth/session/csrf', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ csrfToken: csrf, mode: 'PREAUTH' }) });
  });
  await page.route('**/api/v1/auth/local', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ csrfToken: `${csrf}-login`, mode: 'TENANT_AUTHENTICATED' }) });
  });

  await page.goto('/login');
  await page.getByLabel('Email').fill('person@example.com');
  await page.getByLabel('Password').fill('current password');
  await page.getByRole('button', { name: 'Continue', exact: true }).click();
  await page.waitForTimeout(700);
  await page.evaluate(() => {
    window.history.pushState({}, '', '/application');
    window.dispatchEvent(new PopStateEvent('popstate'));
  });

  await expect(page.getByRole('heading', { name: 'Application' })).toBeVisible();
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

test('BFF timeout also bounds stalled response-body parsing', async ({ page }) => {
  await page.addInitScript(() => {
    const originalFetch = window.fetch.bind(window);
    window.fetch = async (input, init) => {
      const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
      if (url.endsWith('/api/v1/auth/local')) {
        return {
          ok: true,
          status: 200,
          json: () => new Promise<never>((_resolve, reject) => {
            const signal = init?.signal;
            if (!signal) return;
            if (signal.aborted) {
              reject(new DOMException('Aborted', 'AbortError'));
              return;
            }
            signal.addEventListener(
              'abort',
              () => reject(new DOMException('Aborted', 'AbortError')),
              { once: true },
            );
          }),
        } as Response;
      }
      return originalFetch(input, init);
    };
  });
  await page.route('**/api/v1/auth/session/csrf', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ csrfToken: csrf, mode: 'PREAUTH' }) });
  });

  await page.goto('/login');
  await page.getByLabel('Email').fill('person@example.com');
  await page.getByLabel('Password').fill('current password');
  await page.getByRole('button', { name: 'Continue', exact: true }).click();

  await expect(page.getByRole('alert')).toHaveText('BFF_REQUEST_TIMEOUT', { timeout: 4_500 });
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

test('application error boundary hides caught render details', async ({ page }) => {
  await page.addInitScript(() => {
    Date.prototype.toLocaleString = () => { throw new Error('person@example.com secret-render-detail'); };
  });
  await page.route('**/api/v1/auth/session', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: '{"mode":"AUTHENTICATED_ONBOARDING","authenticated":true,"tenantSelected":false}' });
  });
  await page.route('**/api/v1/identity/invitations/received', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        invitations: [{
          invitationId: '11111111-1111-4111-8111-111111111111',
          tenantId: '22222222-2222-4222-8222-222222222222',
          tenantName: 'Sample Tenant',
          tenantSlug: 'sample-tenant',
          state: 'PENDING',
          expiresAt: '2030-01-01T00:00:00Z',
        }],
      }),
    });
  });

  await page.goto('/tenants/manage');

  await expect(page.getByRole('heading', { name: 'The application could not continue' })).toBeVisible();
  await expect(page.getByText(/person@example|secret-render-detail/)).toHaveCount(0);
});
