import type { AppModel } from './appReducer';

const LEGACY_KEY = 'hooshix.frontend.state';
const KEY = 'hooshix.frontend.registration';
const VERSION = 2;

type StoredState = {
  version: number;
  contact: string;
};

export function loadState(): Partial<AppModel> {
  try {
    window.localStorage.removeItem(LEGACY_KEY);
  } catch {
    // Browser storage is optional UX state and never authority.
  }
  try {
    const raw = window.sessionStorage.getItem(KEY);
    if (!raw) return {};
    const parsed = JSON.parse(raw) as StoredState;
    if (
      parsed.version !== VERSION
      || typeof parsed.contact !== 'string'
      || parsed.contact.length === 0
      || parsed.contact.length > 254
    ) {
      window.sessionStorage.removeItem(KEY);
      return {};
    }
    return { contact: parsed.contact };
  } catch {
    return {};
  }
}

export function saveState(state: AppModel): void {
  try {
    window.localStorage.removeItem(LEGACY_KEY);
  } catch {
    // Browser storage is optional UX state and never authority.
  }
  try {
    if (!state.contact || state.authenticated) {
      window.sessionStorage.removeItem(KEY);
      return;
    }
    const payload: StoredState = { version: VERSION, contact: state.contact };
    window.sessionStorage.setItem(KEY, JSON.stringify(payload));
  } catch {
    // Quota, privacy mode, and disabled storage must not crash the application.
  }
}
