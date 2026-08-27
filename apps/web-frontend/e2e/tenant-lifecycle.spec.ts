import { test, expect } from '@playwright/test';

const tenantId = '11111111-1111-4111-8111-111111111111';
const invitationId = '22222222-2222-4222-8222-222222222222';
const csrf = 'synthetic-csrf-token-with-at-least-thirty-two-characters';

test('tenant management handles received invitations and clears deleted tenant state', async ({ page }) => {
  await page.addInitScript(({ selectedTenantId }) => {
    window.localStorage.setItem(
      'hooshix.frontend.state',
      JSON.stringify({
        version: 1,
        data: {
          contact: 'person@example.test',
          authenticated: true,
          selectedTenantId,
          status: 'ready',
          registrationStatus: 'idle',
          verificationStatus: 'idle',
          lastError: null,
        },
      }),
    );
  }, { selectedTenantId: tenantId });
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
    expect(route.request().headers()['x-csrf-token']).toBe(csrf);
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

  await page.goto('/tenants/manage');
  await expect(page.getByText('Sample Tenant — PENDING')).toBeVisible();
  await page.getByRole('button', { name: 'decline' }).click();
  await expect.poll(() => declined).toBe(true);
  await page.getByRole('button', { name: 'Delete selected tenant' }).click();
  await expect(page.getByRole('status')).toContainText('ACTIVE → DELETED (pending)');

  const persisted = await page.evaluate(() => window.localStorage.getItem('hooshix.frontend.state'));
  expect(persisted).toContain('"selectedTenantId":null');
  expect(persisted).not.toContain('refresh');
});
