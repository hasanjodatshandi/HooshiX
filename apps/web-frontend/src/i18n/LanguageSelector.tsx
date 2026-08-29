import { useI18n } from './I18nProvider';

export function LanguageSelector() {
  const { locale, setLocale, t } = useI18n();
  return <label>
    {t('language')}{' '}
    <select value={locale} onChange={(event) => setLocale(event.target.value === 'fa' ? 'fa' : 'en')}>
      <option value="fa">{t('languagePersian')}</option>
      <option value="en">{t('languageEnglish')}</option>
    </select>
  </label>;
}
