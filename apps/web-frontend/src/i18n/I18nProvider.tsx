import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { applyDirection, type Locale } from './direction';
import { resources, type MessageKey } from './resources';

type Variables = Readonly<Record<string, string | number>>;
type I18nValue = {
  locale: Locale;
  setLocale: (locale: Locale) => void;
  t: (key: MessageKey, variables?: Variables) => string;
};

const I18nContext = createContext<I18nValue | null>(null);

function initialLocale(): Locale {
  return navigator.languages.some((language) => language.toLowerCase().startsWith('fa')) ? 'fa' : 'en';
}

export function I18nProvider({ children }: { children: ReactNode }) {
  const [locale, setLocale] = useState<Locale>(initialLocale);

  useEffect(() => applyDirection(locale), [locale]);

  const value = useMemo<I18nValue>(() => ({
    locale,
    setLocale,
    t(key, variables = {}) {
      return Object.entries(variables).reduce(
        (message, [name, replacement]) => message.replaceAll(`{${name}}`, String(replacement)),
        resources[locale][key],
      );
    },
  }), [locale]);

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useI18n() {
  const value = useContext(I18nContext);
  if (!value) throw new Error('I18nProvider is missing');
  return value;
}
