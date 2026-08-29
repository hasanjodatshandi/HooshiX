import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { I18nProvider, useI18n } from './I18nProvider';
import { LanguageSelector } from './LanguageSelector';

function Example() {
  const { t } = useI18n();
  return <><LanguageSelector /><h1>{t('registration')}</h1></>;
}

describe('I18nProvider', () => {
  it('switches catalog, language, and writing direction together', async () => {
    const user = userEvent.setup();
    render(<I18nProvider><Example /></I18nProvider>);

    await user.selectOptions(screen.getByRole('combobox'), 'fa');
    expect(screen.getByRole('heading', { name: 'ثبت‌نام' })).toBeInTheDocument();
    expect(document.documentElement).toHaveAttribute('lang', 'fa');
    expect(document.documentElement).toHaveAttribute('dir', 'rtl');

    await user.selectOptions(screen.getByRole('combobox'), 'en');
    expect(screen.getByRole('heading', { name: 'Registration' })).toBeInTheDocument();
    expect(document.documentElement).toHaveAttribute('lang', 'en');
    expect(document.documentElement).toHaveAttribute('dir', 'ltr');
  });
});
