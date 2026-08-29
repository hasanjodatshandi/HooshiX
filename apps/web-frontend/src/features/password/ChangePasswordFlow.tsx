import { type FormEvent, useState } from 'react';
import { bffClient } from '../../api/bffClient';
import { getErrorMessage } from '../../errors/getErrorMessage';
import { normalizedCurrentPassword, normalizedNewPassword } from '../../validation/userInput';
import { useI18n } from '../../i18n/I18nProvider';

export function ChangePasswordFlow() {
  const { t } = useI18n();
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [status, setStatus] = useState<'idle' | 'saving' | 'changed' | 'failed'>('idle');
  const [error, setError] = useState('');

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (status === 'saving') return;
    setStatus('saving');
    setError('');
    try {
      await bffClient.changePassword({
        currentPassword: normalizedCurrentPassword(currentPassword),
        newPassword: normalizedNewPassword(newPassword),
      });
      setCurrentPassword('');
      setNewPassword('');
      setStatus('changed');
    } catch (cause) {
      setError(getErrorMessage(cause));
      setStatus('failed');
    } finally {
      setCurrentPassword('');
      setNewPassword('');
    }
  }

  return <main><section aria-labelledby="password-change-title">
    <h1 id="password-change-title">{t('changePassword')}</h1>
    <form onSubmit={submit}>
      <label htmlFor="current-password">{t('currentPassword')}</label>
      <input id="current-password" name="currentPassword" type="password" autoComplete="current-password" required value={currentPassword} onChange={(event) => setCurrentPassword(event.target.value)} />
      <label htmlFor="new-password">{t('newPassword')}</label>
      <input id="new-password" name="newPassword" type="password" autoComplete="new-password" minLength={12} maxLength={128} required value={newPassword} onChange={(event) => setNewPassword(event.target.value)} />
      <button type="submit" disabled={status === 'saving'}>{t('savePassword')}</button>
    </form>
    <p role="status">{status === 'changed' ? t('passwordChanged') : ''}</p>
    <p role="alert">{error}</p>
  </section></main>;
}
