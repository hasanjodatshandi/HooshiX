import { type FormEvent, useState } from 'react';
import { bffClient } from '../../api/bffClient';
import { useAppState } from '../../state/appState';
import { getErrorMessage } from '../../errors/getErrorMessage';
import { navigate } from '../../navigation/navigate';
import * as actions from '../../state/appActions';
import { canonicalEmail, verificationCode } from '../../validation/userInput';
import { useI18n } from '../../i18n/I18nProvider';

export function VerificationFlow() {
  const { t } = useI18n();
  const [code, setCode] = useState('');

  const { state: appState, dispatch } = useAppState();
  const contact = appState.contact;
  const status = appState.verificationStatus;
  const error = appState.lastError ?? '';

  async function confirm(event: FormEvent) {
    event.preventDefault();
    if (status === 'loading') return;
    dispatch(actions.clearError());
    dispatch(actions.verificationRequestStarted());
    try {
      await bffClient.confirm({
      channel: 'EMAIL',
      contact: canonicalEmail(contact),
      code: verificationCode(code),
      });
      dispatch(actions.verificationCompleted());
      navigate('/application');
    } catch (e) {
      dispatch(actions.verificationFailed(getErrorMessage(e)));
    } finally {
      setCode('');
    }
  }

  return <main><section aria-labelledby="verification-title">
    <h2 id="verification-title">{t('verification')}</h2>
    <form onSubmit={(event) => void confirm(event)}>
      <label htmlFor="verification-code">{t('code')}</label>
      <input id="verification-code" aria-label={t('verificationCode')} inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{8}" maxLength={8} required value={code} onChange={(event) => setCode(event.target.value)} />
      <button type="submit" disabled={status === 'loading'}>{t('confirm')}</button>
    </form>
    <p role="status">{status === 'loading' ? t('submitting') : status === 'success' ? t('completed') : ''}</p>
    <p role="alert">{error}</p>
  </section></main>;
}
