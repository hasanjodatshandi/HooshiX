import { describe, expect, it } from 'vitest';
import { appReducer, initialAppModel, type AppEvent } from './appReducer';

describe('appReducer', () => {
  it('restores session once and fails closed when restoration fails', () => {
    const restored = appReducer(
      { ...initialAppModel, contact: 'a@example.com', selectedTenantId: 'tenant' },
      { type: 'SESSION_RESTORED', authenticated: true, tenantSelected: true },
    );
    expect(restored).toMatchObject({
      contact: '',
      authenticated: true,
      tenantSelected: true,
      selectedTenantId: 'tenant',
      sessionStatus: 'ready',
    });
    expect(appReducer(restored, { type: 'SESSION_RESTORE_FAILED', error: 'late' })).toBe(restored);

    const failed = appReducer(initialAppModel, { type: 'SESSION_RESTORE_FAILED', error: 'offline' });
    expect(failed).toMatchObject({
      authenticated: false,
      tenantSelected: false,
      selectedTenantId: null,
      sessionStatus: 'ready',
      lastError: 'offline',
    });
  });

  it('applies registration and verification transitions', () => {
    const events: AppEvent[] = [
      { type: 'REGISTRATION_STARTED', contact: 'a@example.com' },
      { type: 'REGISTRATION_REQUEST_STARTED' },
      { type: 'REGISTRATION_SUCCEEDED' },
      { type: 'VERIFICATION_REQUEST_STARTED' },
      { type: 'VERIFICATION_FAILED', error: 'INVALID_CODE' },
      { type: 'ERROR_CLEARED' },
      { type: 'VERIFICATION_COMPLETED' },
    ];
    const state = events.reduce(appReducer, initialAppModel);
    expect(state).toMatchObject({
      contact: '',
      authenticated: true,
      registrationStatus: 'success',
      verificationStatus: 'success',
      lastError: null,
    });
    expect(appReducer(state, { type: 'REGISTRATION_FAILED', error: 'FAILED' }))
      .toMatchObject({ registrationStatus: 'failed', lastError: 'FAILED' });
    expect(appReducer(state, { type: 'REGISTRATION_CLEARED' }))
      .toMatchObject({ contact: '', registrationStatus: 'idle', verificationStatus: 'idle' });
  });

  it('clears authority-derived state on expiry, erasure, and tenant changes', () => {
    const authenticated = {
      ...initialAppModel,
      authenticated: true,
      tenantSelected: true,
      selectedTenantId: 'old',
      sessionStatus: 'ready' as const,
    };
    expect(appReducer(authenticated, { type: 'SESSION_EXPIRED' })).toMatchObject({
      authenticated: false,
      tenantSelected: false,
      selectedTenantId: null,
      status: 'expired',
    });
    expect(appReducer(authenticated, { type: 'ACCOUNT_ERASED' })).toEqual({
      ...initialAppModel,
      sessionStatus: 'ready',
    });
    expect(appReducer(authenticated, { type: 'TENANT_SELECTED', tenantId: 'new' }))
      .toMatchObject({ tenantSelected: true, selectedTenantId: 'new' });
    expect(appReducer(authenticated, { type: 'TENANT_CLEARED' }))
      .toMatchObject({ tenantSelected: false, selectedTenantId: null });
    expect(appReducer(initialAppModel, { type: 'LOGIN_SUCCEEDED', tenantSelected: false }))
      .toMatchObject({ authenticated: true, tenantSelected: false, sessionStatus: 'ready' });
  });
});
