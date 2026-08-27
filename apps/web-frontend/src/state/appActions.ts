import type { AppEvent } from './appReducer';

export function registrationStarted(contact: string): AppEvent {
  return { type: 'REGISTRATION_STARTED', contact };
}

export function registrationRequestStarted(): AppEvent {
  return { type: 'REGISTRATION_REQUEST_STARTED' };
}

export function registrationSucceeded(): AppEvent {
  return { type: 'REGISTRATION_SUCCEEDED' };
}

export function registrationFailed(error: string): AppEvent {
  return { type: 'REGISTRATION_FAILED', error };
}

export function verificationRequestStarted(): AppEvent {
  return { type: 'VERIFICATION_REQUEST_STARTED' };
}

export function verificationCompleted(): AppEvent {
  return { type: 'VERIFICATION_COMPLETED' };
}

export function verificationFailed(error: string): AppEvent {
  return { type: 'VERIFICATION_FAILED', error };
}

export function sessionExpired(): AppEvent {
  return { type: 'SESSION_EXPIRED' };
}

export function tenantSelected(tenantId: string): AppEvent {
  return { type: 'TENANT_SELECTED', tenantId };
}

export function tenantCleared(): AppEvent {
  return { type: 'TENANT_CLEARED' };
}

export function clearError(): AppEvent {
  return { type: 'ERROR_CLEARED' };
}

export function loginSucceeded() { return { type: 'LOGIN_SUCCEEDED' as const }; }
