import { expect, test } from '@playwright/test';

const conversationId = '11111111-1111-4111-8111-111111111111';
const runId = '22222222-2222-4222-8222-222222222222';
const now = '2026-09-20T12:00:00Z';
const csrf = 'synthetic-csrf-token-with-at-least-thirty-two-characters';

test('creates a conversation, submits a run, polls it, and renders the response as text', async ({ page }) => {
  await page.route('**/api/v1/auth/session', (route) => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: '{"mode":"AUTHENTICATED_TENANT","authenticated":true,"tenantSelected":true}',
  }));
  await page.route('**/api/v1/auth/session/csrf', (route) => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ csrfToken: csrf, mode: 'AUTHENTICATED_TENANT' }),
  }));

  let created = false;
  let runReads = 0;
  await page.route('**/api/v1/conversations?pageSize=100', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ conversations: created ? [{
        conversationId,
        title: 'Project plan',
        lifecycle: 'ACTIVE',
        version: 1,
        createdAt: now,
        lastActivityAt: now,
      }] : [], nextPageToken: '' }),
    });
  });
  await page.route('**/api/v1/conversations', async (route) => {
    created = true;
    await route.fulfill({
      status: 201,
      contentType: 'application/json',
      body: JSON.stringify({ conversationId, title: 'Project plan', lifecycle: 'ACTIVE', version: 1, createdAt: now, lastActivityAt: now }),
    });
  });
  await page.route(`**/api/v1/conversations/${conversationId}/messages?pageSize=100`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        messages: runReads > 0 ? [{
          messageId: '33333333-3333-4333-8333-333333333333',
          conversationId,
          role: 'ASSISTANT',
          content: '<script>alert(1)</script>Safe answer',
          ordinal: 2,
          createdAt: now,
        }] : [],
        nextPageToken: '',
      }),
    });
  });
  await page.route(`**/api/v1/conversations/${conversationId}/runs`, async (route) => {
    await route.fulfill({
      status: 202,
      contentType: 'application/json',
      body: JSON.stringify({ runId, conversationId, state: 'QUEUED', cancellationRequested: false, createdAt: now }),
    });
  });
  await page.route(`**/api/v1/conversations/${conversationId}/runs/${runId}`, async (route) => {
    runReads += 1;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ runId, conversationId, state: 'SUCCEEDED', cancellationRequested: false, createdAt: now, completedAt: now }),
    });
  });

  await page.goto('/conversations');
  await page.getByLabel('Conversation title').fill('Project plan');
  await page.getByRole('button', { name: 'Create conversation' }).click();
  await expect(page.getByRole('heading', { name: 'Project plan' })).toBeVisible();
  await page.getByLabel('Message').fill('Give me a safe answer');
  await page.getByRole('button', { name: 'Send message' }).click();

  await expect(page.getByText('Response state: SUCCEEDED')).toBeVisible();
  await expect(page.getByText('<script>alert(1)</script>Safe answer')).toBeVisible();
  await expect(page.locator('script')).toHaveCount(1);
});
