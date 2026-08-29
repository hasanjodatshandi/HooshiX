export type AsyncStatus = 'idle' | 'loading' | 'success' | 'failed';
export type SessionStatus = 'checking' | 'ready';

export type AppEvent =
  | { type: 'SESSION_RESTORED'; authenticated: boolean; tenantSelected: boolean }
  | { type: 'SESSION_RESTORE_FAILED'; error: string }
  | { type: 'REGISTRATION_STARTED'; contact: string }
  | { type: 'REGISTRATION_CLEARED' }
  | { type: 'REGISTRATION_REQUEST_STARTED' }
  | { type: 'REGISTRATION_SUCCEEDED' }
  | { type: 'REGISTRATION_FAILED'; error: string }
  | { type: 'VERIFICATION_REQUEST_STARTED' }
  | { type: 'VERIFICATION_COMPLETED' }
  | { type: 'VERIFICATION_FAILED'; error: string }
  | { type: 'ERROR_CLEARED' }
  | { type: 'SESSION_EXPIRED' }
  | { type: 'ACCOUNT_ERASED' }
  | { type: 'TENANT_SELECTED'; tenantId: string }
  | { type: 'TENANT_CLEARED' }
  | { type: 'LOGIN_SUCCEEDED'; tenantSelected: boolean };

export type AppModel = {
  contact: string;
  authenticated: boolean;
  tenantSelected: boolean;
  selectedTenantId: string | null;
  sessionStatus: SessionStatus;
  status: 'ready' | 'expired';
  registrationStatus: AsyncStatus;
  verificationStatus: AsyncStatus;
  lastError: string | null;
};

export const initialAppModel: AppModel = {
  contact: '',
  authenticated: false,
  tenantSelected: false,
  selectedTenantId: null,
  sessionStatus: 'checking',
  status: 'ready',
  registrationStatus: 'idle',
  verificationStatus: 'idle',
  lastError: null,
};

export function appReducer(state: AppModel, event: AppEvent): AppModel {
  switch (event.type) {
    case 'SESSION_RESTORED': return {
      ...state,
      contact: event.authenticated ? '' : state.contact,
      authenticated: event.authenticated,
      tenantSelected: event.tenantSelected,
      selectedTenantId: event.tenantSelected ? state.selectedTenantId : null,
      sessionStatus: 'ready',
      status: event.authenticated ? 'ready' : state.status,
    };
    case 'SESSION_RESTORE_FAILED': return {
      ...state,
      authenticated: false,
      tenantSelected: false,
      selectedTenantId: null,
      sessionStatus: 'ready',
      lastError: event.error,
    };
    case 'REGISTRATION_STARTED': return { ...state, contact: event.contact };
    case 'REGISTRATION_CLEARED': return {
      ...state,
      contact: '',
      registrationStatus: 'idle',
      verificationStatus: 'idle',
      lastError: null,
    };
    case 'REGISTRATION_REQUEST_STARTED': return { ...state, registrationStatus: 'loading', lastError: null };
    case 'REGISTRATION_SUCCEEDED': return { ...state, registrationStatus: 'success' };
    case 'REGISTRATION_FAILED': return { ...state, registrationStatus: 'failed', lastError: event.error };
    case 'VERIFICATION_REQUEST_STARTED': return { ...state, verificationStatus: 'loading', lastError: null };
    case 'VERIFICATION_COMPLETED': return { ...state, contact: '', verificationStatus: 'success', authenticated: true };
    case 'VERIFICATION_FAILED': return { ...state, verificationStatus: 'failed', lastError: event.error };
    case 'ERROR_CLEARED': return { ...state, lastError: null };
    case 'SESSION_EXPIRED': return { ...state, authenticated: false, tenantSelected: false, selectedTenantId: null, status: 'expired' };
    case 'ACCOUNT_ERASED': return { ...initialAppModel, sessionStatus: 'ready' };
    case 'TENANT_SELECTED': return { ...state, tenantSelected: true, selectedTenantId: event.tenantId };
    case 'TENANT_CLEARED': return { ...state, tenantSelected: false, selectedTenantId: null };
    case 'LOGIN_SUCCEEDED': return { ...state, authenticated: true, tenantSelected: event.tenantSelected, status: 'ready' };
  }
}
