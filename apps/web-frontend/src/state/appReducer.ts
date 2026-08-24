export type AsyncStatus = 'idle' | 'loading' | 'success' | 'failed';

export type AppEvent =
  | { type: 'STATE_REHYDRATED'; payload: AppModel }
  | { type: 'REGISTRATION_STARTED'; contact: string }
  | { type: 'REGISTRATION_REQUEST_STARTED' }
  | { type: 'REGISTRATION_SUCCEEDED' }
  | { type: 'REGISTRATION_FAILED'; error: string }
  | { type: 'VERIFICATION_REQUEST_STARTED' }
  | { type: 'VERIFICATION_COMPLETED' }
  | { type: 'VERIFICATION_FAILED'; error: string }
  | { type: 'ERROR_CLEARED' }
  | { type: 'SESSION_EXPIRED' }
  | { type: 'TENANT_SELECTED'; tenantId: string }
  | { type: 'LOGIN_SUCCEEDED' };

export type AppModel = {
  contact: string;
  authenticated: boolean;
  selectedTenantId: string | null;
  status: 'ready' | 'expired';
  registrationStatus: AsyncStatus;
  verificationStatus: AsyncStatus;
  lastError: string | null;
};

export const initialAppModel: AppModel = {
  contact: '',
  authenticated: false,
  selectedTenantId: null,
  status: 'ready',
  registrationStatus: 'idle',
  verificationStatus: 'idle',
  lastError: null,
};

export function appReducer(state: AppModel, event: AppEvent): AppModel {
  switch (event.type) {
    case 'STATE_REHYDRATED': return event.payload;
    case 'REGISTRATION_STARTED': return { ...state, contact: event.contact };
    case 'REGISTRATION_REQUEST_STARTED': return { ...state, registrationStatus: 'loading', lastError: null };
    case 'REGISTRATION_SUCCEEDED': return { ...state, registrationStatus: 'success' };
    case 'REGISTRATION_FAILED': return { ...state, registrationStatus: 'failed', lastError: event.error };
    case 'VERIFICATION_REQUEST_STARTED': return { ...state, verificationStatus: 'loading', lastError: null };
    case 'VERIFICATION_COMPLETED': return { ...state, verificationStatus: 'success', authenticated: true };
    case 'VERIFICATION_FAILED': return { ...state, verificationStatus: 'failed', lastError: event.error };
    case 'ERROR_CLEARED': return { ...state, lastError: null };
    case 'SESSION_EXPIRED': return { ...state, authenticated: false, status: 'expired' };
    case 'TENANT_SELECTED': return { ...state, selectedTenantId: event.tenantId };
    case 'LOGIN_SUCCEEDED': return { ...state, authenticated: true, status: 'ready' };
  }
}
