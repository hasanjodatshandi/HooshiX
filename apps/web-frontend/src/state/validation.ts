import type { AppModel } from './appReducer';

function isAsyncStatus(value: unknown): value is AppModel['registrationStatus'] {
  return value === 'idle' || value === 'loading' || value === 'success' || value === 'failed';
}

export function isValidState(value: Partial<AppModel>): value is AppModel {
  return typeof value.contact === 'string'
    && typeof value.authenticated === 'boolean'
    && (value.selectedTenantId === null || typeof value.selectedTenantId === 'string')
    && (value.status === 'ready' || value.status === 'expired')
    && isAsyncStatus(value.registrationStatus)
    && isAsyncStatus(value.verificationStatus)
    && (value.lastError === null || typeof value.lastError === 'string');
}
