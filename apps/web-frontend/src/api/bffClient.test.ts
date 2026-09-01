import { afterEach, describe, expect, it, vi } from 'vitest';
import { bootstrapSession } from './bffClient';

describe('BFF request identity boundary', () => {
  afterEach(() => vi.restoreAllMocks());

  it('sends the business UUID as Idempotency-Key and not as telemetry request ID', async () => {
    const idempotencyKey = '550e8400-e29b-41d4-a716-446655440001';
    vi.spyOn(crypto, 'randomUUID').mockReturnValue(idempotencyKey);
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ csrfToken: 'c'.repeat(32), mode: 'PREAUTH' }), {
        status: 201,
        headers: { 'content-type': 'application/json' },
      }),
    );

    await bootstrapSession();

    const init = fetchMock.mock.calls[0]?.[1];
    const headers = new Headers(init?.headers);
    expect(headers.get('idempotency-key')).toBe(idempotencyKey);
    expect(headers.has('x-request-id')).toBe(false);
    expect(init?.credentials).toBe('same-origin');
  });
});
