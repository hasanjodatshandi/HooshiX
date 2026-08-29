import { type FormEvent, useState } from 'react';
import { bffClient, type MfaProof } from '../../api/bffClient';
import { getErrorMessage } from '../../errors/getErrorMessage';
import { canonicalEmail, normalizedNewPassword, recoveryCode, totpCode, verificationCode } from '../../validation/userInput';
import { useI18n } from '../../i18n/I18nProvider';

export function PasswordRecoveryFlow() {
  const { t } = useI18n();
  const [contact, setContact] = useState('');
  const [code, setCode] = useState('');
  const [password, setPassword] = useState('');
  const [mfaType, setMfaType] = useState<MfaProof['type']>('TOTP');
  const [mfaCode, setMfaCode] = useState('');
  const [stage, setStage] = useState<'request' | 'confirm' | 'complete'>('request');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  async function request(event: FormEvent) {
    event.preventDefault();
    if (busy) return;
    setBusy(true);
    setError('');
    try {
      await bffClient.requestPasswordRecovery(canonicalEmail(contact));
      setStage('confirm');
    } catch (cause) {
      setError(getErrorMessage(cause));
    } finally {
      setBusy(false);
    }
  }

  async function confirm(event: FormEvent) {
    event.preventDefault();
    if (busy) return;
    setBusy(true);
    setError('');
    try {
      await bffClient.confirmPasswordRecovery({
        contact: canonicalEmail(contact),
        code: verificationCode(code),
        newPassword: normalizedNewPassword(password),
        ...(mfaCode ? { mfaProof: { type: mfaType, code: mfaType === 'TOTP' ? totpCode(mfaCode) : recoveryCode(mfaCode) } } : {}),
      });
      setCode('');
      setPassword('');
      setMfaCode('');
      setContact('');
      setStage('complete');
    } catch (cause) {
      setError(getErrorMessage(cause));
    } finally {
      setCode('');
      setPassword('');
      setMfaCode('');
      setBusy(false);
    }
  }

  return <main><section aria-labelledby="password-recovery-title">
    <h1 id="password-recovery-title">{t('passwordRecovery')}</h1>
    {stage === 'request' && <form onSubmit={request}>
      <label htmlFor="recovery-contact">{t('email')}</label>
      <input id="recovery-contact" type="email" autoComplete="email" maxLength={254} required value={contact} onChange={(event) => setContact(event.target.value)} />
      <button type="submit" disabled={busy}>{t('sendRecoveryCode')}</button>
    </form>}
    {stage === 'confirm' && <form onSubmit={confirm}>
      <label htmlFor="recovery-code">{t('eightDigitCode')}</label>
      <input id="recovery-code" inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{8}" maxLength={8} required value={code} onChange={(event) => setCode(event.target.value)} />
      <label htmlFor="recovery-password">{t('newPassword')}</label>
      <input id="recovery-password" type="password" autoComplete="new-password" minLength={12} maxLength={128} required value={password} onChange={(event) => setPassword(event.target.value)} />
      <fieldset>
        <legend>{t('twoFactorProofRequired')}</legend>
        <label htmlFor="recovery-mfa-type">{t('proofType')}</label>
        <select id="recovery-mfa-type" value={mfaType} onChange={(event) => { setMfaType(event.target.value as MfaProof['type']); setMfaCode(''); }}>
          <option value="TOTP">{t('authenticatorCode')}</option>
          <option value="RECOVERY_CODE">{t('recoveryCode')}</option>
        </select>
        <label htmlFor="recovery-mfa-code">{t('twoFactorProof')}</label>
        <input id="recovery-mfa-code" autoComplete="one-time-code" value={mfaCode} onChange={(event) => setMfaCode(event.target.value)} />
      </fieldset>
      <button type="submit" disabled={busy}>{t('resetPassword')}</button>
    </form>}
    <p role="status">{stage === 'complete' ? t('passwordResetComplete') : ''}</p>
    <p role="alert">{error}</p>
  </section></main>;
}
