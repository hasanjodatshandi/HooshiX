import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { initialAppModel } from './appReducer';
import { loadState, saveState } from './storage';

const KEY = 'hooshix.frontend.registration';
const LEGACY_KEY = 'hooshix.frontend.state';

function memoryStorage(entries: Map<string, string>): Storage {
  return {
    get length() { return entries.size; },
    clear: () => entries.clear(),
    getItem: (key) => entries.get(key) ?? null,
    key: (index) => [...entries.keys()][index] ?? null,
    removeItem: (key) => { entries.delete(key); },
    setItem: (key, value) => { entries.set(key, value); },
  };
}

describe('non-authoritative registration storage', () => {
  let legacyEntries: Map<string, string>;
  let registrationEntries: Map<string, string>;

  beforeEach(() => {
    legacyEntries = new Map();
    registrationEntries = new Map();
    vi.spyOn(window, 'localStorage', 'get').mockReturnValue(memoryStorage(legacyEntries));
    vi.spyOn(window, 'sessionStorage', 'get').mockReturnValue(memoryStorage(registrationEntries));
  });

  afterEach(() => vi.restoreAllMocks());

  it('loads only the current version with an already canonical email', () => {
    legacyEntries.set(LEGACY_KEY, 'legacy');
    registrationEntries.set(KEY, JSON.stringify({ version: 2, contact: 'a@example.com' }));

    expect(loadState()).toEqual({ contact: 'a@example.com' });
    expect(window.localStorage.getItem(LEGACY_KEY)).toBeNull();

    registrationEntries.set(KEY, JSON.stringify({ version: 1, contact: 'a@example.com' }));
    expect(loadState()).toEqual({});
    expect(window.sessionStorage.getItem(KEY)).toBeNull();
  });

  it('discards malformed, noncanonical, and unavailable storage state', () => {
    registrationEntries.set(KEY, '{');
    expect(loadState()).toEqual({});
    registrationEntries.set(KEY, JSON.stringify({ version: 2, contact: 'A@EXAMPLE.COM' }));
    expect(loadState()).toEqual({});
    vi.spyOn(window.sessionStorage, 'getItem').mockImplementation(() => {
      throw new DOMException('denied');
    });
    expect(loadState()).toEqual({});
  });

  it('persists only unauthenticated canonical registration contact', () => {
    saveState({ ...initialAppModel, contact: 'a@example.com' });
    expect(JSON.parse(window.sessionStorage.getItem(KEY) ?? '{}')).toEqual({
      version: 2,
      contact: 'a@example.com',
    });

    saveState({ ...initialAppModel, contact: 'A@EXAMPLE.COM' });
    expect(window.sessionStorage.getItem(KEY)).toBeNull();
    saveState({ ...initialAppModel, contact: 'a@example.com', authenticated: true });
    expect(window.sessionStorage.getItem(KEY)).toBeNull();
  });

  it.each(['localStorage', 'sessionStorage'] as const)('never crashes when %s access is disabled', (property) => {
    vi.spyOn(window, property, 'get').mockImplementation(() => {
      throw new DOMException('denied');
    });
    expect(() => saveState({ ...initialAppModel, contact: 'a@example.com' })).not.toThrow();
    expect(() => loadState()).not.toThrow();
  });

  it.each(['setItem', 'removeItem'] as const)('never crashes when optional session %s fails', (method) => {
    vi.spyOn(window.sessionStorage, method).mockImplementation(() => {
      throw new DOMException('denied');
    });
    expect(() => saveState({ ...initialAppModel, contact: 'a@example.com' })).not.toThrow();
    expect(() => saveState({ ...initialAppModel, authenticated: true })).not.toThrow();
    registrationEntries.set(KEY, JSON.stringify({ version: 1, contact: 'a@example.com' }));
    expect(loadState()).toEqual({});
  });
});
