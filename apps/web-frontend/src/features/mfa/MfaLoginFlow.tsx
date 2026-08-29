import { type FormEvent, useState } from 'react';
import { bffClient, type MfaProof } from '../../api/bffClient';
import { getErrorMessage } from '../../errors/getErrorMessage';
import { navigate } from '../../navigation/navigate';
import { routes } from '../../routes/routes';
import * as actions from '../../state/appActions';
import { useAppState } from '../../state/appState';
import { recoveryCode, totpCode } from '../../validation/userInput';
import { useI18n } from '../../i18n/I18nProvider';

export function MfaLoginFlow() {
  const { t } = useI18n();
  const { dispatch } = useAppState();
  const [type, setType] = useState<MfaProof['type']>('TOTP');
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (busy) return;
    setBusy(true);
    setError('');
    try {
      const canonicalCode = type === 'TOTP' ? totpCode(code) : recoveryCode(code);
      const session = await bffClient.completeMfaAuthentication({ type, code: canonicalCode });
      setCode('');
      dispatch(actions.loginSucceeded(session.mode === 'TENANT_AUTHENTICATED'));
      navigate(session.mode === 'TENANT_AUTHENTICATED' ? routes.application : routes.tenantSelection);
    } catch (cause) {
      setError(getErrorMessage(cause));
    } finally {
      setCode('');
      setBusy(false);
    }
  }

  return <main><section aria-labelledby="mfa-login-title">
    <h1 id="mfa-login-title">{t('twoFactorAuthentication')}</h1>
    <form onSubmit={submit}>
      <label htmlFor="mfa-login-type">{t('proofType')}</label>
      <select id="mfa-login-type" value={type} onChange={(event) => { setType(event.target.value as MfaProof['type']); setCode(''); }}>
        <option value="TOTP">{t('authenticatorCode')}</option>
        <option value="RECOVERY_CODE">{t('recoveryCode')}</option>
      </select>
      <label htmlFor="mfa-login-code">{type === 'TOTP' ? t('sixDigitCode') : t('recoveryCode')}</label>
      <input
        id="mfa-login-code"
        value={code}
        onChange={(event) => setCode(event.target.value)}
        inputMode={type === 'TOTP' ? 'numeric' : 'text'}
        autoComplete="one-time-code"
        pattern={type === 'TOTP' ? '[0-9]{6}' : '[A-Za-z2-7]{4}(?:-[A-Za-z2-7]{4}){3}'}
        maxLength={type === 'TOTP' ? 6 : 19}
        required
      />
      <button type="submit" disabled={busy}>{t('verify')}</button>
    </form>
    <p role="alert">{error}</p>
  </section></main>;
}
