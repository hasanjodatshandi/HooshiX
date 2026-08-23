import type { AppModel } from './appReducer';
import { isValidState } from './validation';

const KEY = 'hooshix.frontend.state';
const VERSION = 1;

type StoredState = {
  version: number;
  data: Partial<AppModel>;
};

export function loadState(): Partial<AppModel> {
  try {
    const raw = window.localStorage.getItem(KEY);
    if (!raw) return {};
    const parsed = JSON.parse(raw) as StoredState;
    if (parsed.version !== VERSION || !isValidState(parsed.data)) return {};
    return parsed.data;
  } catch {
    return {};
  }
}

export function saveState(state: AppModel) {
  const safeState: Partial<AppModel> = {
    contact: state.contact,
    authenticated: state.authenticated,
    selectedTenantId: state.selectedTenantId,
    status: state.status,
    registrationStatus: state.registrationStatus,
    verificationStatus: state.verificationStatus,
    lastError: state.lastError,
  };
  const payload: StoredState = { version: VERSION, data: safeState };
  window.localStorage.setItem(KEY, JSON.stringify(payload));
}
