import { test, expect } from '@playwright/test';

const tenantId = '11111111-1111-4111-8111-111111111111';
const invitationId = '22222222-2222-4222-8222-222222222222';
const csrf = 'synthetic-csrf-token-with-at-least-thirty-two-characters';

test('tenant management handles received invitations and clears deleted tenant state', async ({ page }) => {
  await page.route('**/api/v1/auth/session', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: '{"mode":"AUTHENTICATED_ONBOARDING","authenticated":true,"tenantSelected":false}',
    });
  });
  await page.route('**/api/v1/identity/tenants', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        tenants: [{ tenantId, membershipId: invitationId, name: 'Sample Tenant', slug: 'sample-tenant' }],
        suggestedMembershipId: invitationId,
      }),
    });
  });
  await page.route('**/api/v1/auth/session/csrf', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ csrfToken: csrf, mode: 'TENANT_AUTHENTICATED' }),
    });
  });
  await page.route('**/api/v1/identity/invitations/received', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        invitations: [{
          invitationId,
          tenantId,
          tenantName: 'Sample Tenant',
          tenantSlug: 'sample-tenant',
          state: 'PENDING',
          expiresAt: '2030-01-01T00:00:00Z',
        }],
      }),
    });
  });
  await page.route('**/api/v1/identity/invitations', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: '{"invitations":[]}' });
  });
  let declined = false;
  await page.route(`**/api/v1/identity/invitations/${invitationId}/decline`, async (route) => {
    declined = true;
    expect(route.request().headers()['x-csrf-token']).toBe(`${csrf}-selected`);
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ invitationId, state: 'DECLINED' }),
    });
  });
  await page.route(`**/api/v1/identity/tenants/${tenantId}`, async (route) => {
    expect(route.request().method()).toBe('DELETE');
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        tenantId,
        lifecycle: 'ACTIVE',
        targetLifecycle: 'DELETED',
        pending: true,
        csrfToken: `${csrf}-rotated`,
        mode: 'AUTHENTICATED_ONBOARDING',
      }),
    });
  });

  await page.route('**/api/v1/identity/tenant-selection', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ csrfToken: `${csrf}-selected`, tenantId, membershipId: invitationId, mode: 'TENANT_AUTHENTICATED' }),
    });
  });

  await page.goto('/tenant-select');
  await page.getByRole('button', { name: 'Sample Tenant' }).click();
  await expect(page).toHaveURL(/\/application$/);
  await page.getByRole('link', { name: 'Tenant management' }).click();
  await expect(page.getByText('Sample Tenant — PENDING')).toBeVisible();
  await page.getByRole('button', { name: 'decline' }).click();
  await expect.poll(() => declined).toBe(true);
  await page.getByRole('button', { name: 'Delete selected tenant' }).click();
  await expect(page.getByRole('status')).toContainText('ACTIVE → DELETED (pending)');

  const persisted = await page.evaluate(() => JSON.stringify({
    local: { ...window.localStorage },
    session: { ...window.sessionStorage },
  }));
  expect(persisted).not.toMatch(/selectedTenantId|authenticated|refresh|person@example/i);
});
