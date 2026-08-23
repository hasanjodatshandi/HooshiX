import { resources } from './resources';
export type Locale = keyof typeof resources;
export function direction(locale: Locale): 'rtl'|'ltr' { return locale === 'fa' ? 'rtl' : 'ltr'; }
export function applyDirection(locale: Locale) { document.documentElement.dir = direction(locale); document.documentElement.lang = locale; }
