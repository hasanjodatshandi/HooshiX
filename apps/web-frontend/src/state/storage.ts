import type { AppModel } from './appReducer';
import { canonicalEmail } from '../validation/userInput';

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
    const parsed = JSON.parse(raw) as unknown;
    const candidate = parsed && typeof parsed === 'object'
      ? parsed as Partial<StoredState>
      : null;
    const contact = canonicalStoredContact(candidate?.contact);
    if (
      candidate?.version !== VERSION
      || contact === null
    ) {
      window.sessionStorage.removeItem(KEY);
      return {};
    }
    return { contact };
  } catch {
    return {};
  }
}

function canonicalStoredContact(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  try {
    const canonical = canonicalEmail(value);
    return canonical === value ? canonical : null;
  } catch {
    return null;
  }
}

export function saveState(state: AppModel): void {
  try {
    window.localStorage.removeItem(LEGACY_KEY);
  } catch {
    // Browser storage is optional UX state and never authority.
  }
  try {
    const contact = canonicalStoredContact(state.contact);
    if (contact === null || state.authenticated) {
      window.sessionStorage.removeItem(KEY);
      return;
    }
    const payload: StoredState = { version: VERSION, contact };
    window.sessionStorage.setItem(KEY, JSON.stringify(payload));
  } catch {
    // Quota, privacy mode, and disabled storage must not crash the application.
  }
}
