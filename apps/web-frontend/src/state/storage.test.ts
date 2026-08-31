import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { initialAppModel } from './appReducer';
import { loadState, saveState } from './storage';

const KEY = 'hooshix.frontend.registration';
const LEGACY_KEY = 'hooshix.frontend.state';

describe('non-authoritative registration storage', () => {
  beforeEach(() => {
    window.localStorage.clear();
    window.sessionStorage.clear();
  });

  afterEach(() => vi.restoreAllMocks());

  it('loads only the current version with an already canonical email', () => {
    window.localStorage.setItem(LEGACY_KEY, 'legacy');
    window.sessionStorage.setItem(KEY, JSON.stringify({ version: 2, contact: 'a@example.com' }));

    expect(loadState()).toEqual({ contact: 'a@example.com' });
    expect(window.localStorage.getItem(LEGACY_KEY)).toBeNull();

    window.sessionStorage.setItem(KEY, JSON.stringify({ version: 1, contact: 'a@example.com' }));
    expect(loadState()).toEqual({});
    expect(window.sessionStorage.getItem(KEY)).toBeNull();
  });

  it('discards malformed, noncanonical, and unavailable storage state', () => {
    window.sessionStorage.setItem(KEY, '{');
    expect(loadState()).toEqual({});
    window.sessionStorage.setItem(KEY, JSON.stringify({ version: 2, contact: 'A@EXAMPLE.COM' }));
    expect(loadState()).toEqual({});
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
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

  it('never crashes when browser storage is disabled', () => {
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
      throw new DOMException('denied');
    });
    expect(() => saveState({ ...initialAppModel, contact: 'a@example.com' })).not.toThrow();
    expect(() => loadState()).not.toThrow();
  });
});
