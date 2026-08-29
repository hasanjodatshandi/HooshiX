import { type FormEvent, useEffect, useState } from 'react';
import { bffClient, type MfaProof, type MfaStatus, type TotpEnrollment } from '../../api/bffClient';
import { getErrorMessage } from '../../errors/getErrorMessage';
import { recoveryCode, totpCode } from '../../validation/userInput';
import { useI18n } from '../../i18n/I18nProvider';

function canonicalProof(type: MfaProof['type'], code: string): MfaProof {
  return { type, code: type === 'TOTP' ? totpCode(code) : recoveryCode(code) };
}

export function MfaSettingsFlow() {
  const { t } = useI18n();
  const [status, setStatus] = useState<MfaStatus | null>(null);
  const [enrollment, setEnrollment] = useState<TotpEnrollment | null>(null);
  const [recoveryCodes, setRecoveryCodes] = useState<readonly string[]>([]);
  const [proofType, setProofType] = useState<MfaProof['type']>('TOTP');
  const [proofCode, setProofCode] = useState('');
  const [confirmationCode, setConfirmationCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    const controller = new AbortController();
    void bffClient.getMfaStatus({ signal: controller.signal })
      .then(setStatus)
      .catch((cause) => {
        if (!controller.signal.aborted) setError(getErrorMessage(cause));
      });
    return () => controller.abort();
  }, []);

  async function run(operation: () => Promise<void>) {
    if (busy) return;
    setBusy(true);
    setError('');
    try {
      await operation();
    } catch (cause) {
      setError(getErrorMessage(cause));
    } finally {
      setProofCode('');
      setConfirmationCode('');
      setBusy(false);
    }
  }

  function proof(): MfaProof | undefined {
    if (!status?.totpEnabled) return undefined;
    return canonicalProof(proofType, proofCode);
  }

  async function start(event: FormEvent) {
    event.preventDefault();
    await run(async () => {
      setRecoveryCodes([]);
      setEnrollment(await bffClient.startTotpEnrollment(proof()));
      setProofCode('');
    });
  }

  async function confirm(event: FormEvent) {
    event.preventDefault();
    if (!enrollment) return;
    await run(async () => {
      const result = await bffClient.confirmTotpEnrollment({
        enrollmentChallenge: enrollment.enrollmentChallenge,
        totpCode: totpCode(confirmationCode),
      });
      setConfirmationCode('');
      setEnrollment(null);
      setRecoveryCodes(result.recoveryCodes);
      setStatus({ totpEnabled: true, recoveryCodesRemaining: result.recoveryCodes.length });
    });
  }

  async function disable() {
    await run(async () => {
      await bffClient.disableTotp(canonicalProof(proofType, proofCode));
      setProofCode('');
      setEnrollment(null);
      setRecoveryCodes([]);
      setStatus({ totpEnabled: false, recoveryCodesRemaining: 0 });
    });
  }

  async function rotate() {
    await run(async () => {
      const result = await bffClient.rotateRecoveryCodes(canonicalProof(proofType, proofCode));
      setProofCode('');
      setRecoveryCodes(result.recoveryCodes);
      setStatus({ totpEnabled: true, recoveryCodesRemaining: result.recoveryCodes.length });
    });
  }

  const proofFields = status?.totpEnabled && <>
    <label htmlFor="mfa-settings-proof-type">{t('currentProofType')}</label>
    <select id="mfa-settings-proof-type" value={proofType} onChange={(event) => { setProofType(event.target.value as MfaProof['type']); setProofCode(''); }}>
      <option value="TOTP">{t('authenticatorCode')}</option>
      <option value="RECOVERY_CODE">{t('recoveryCode')}</option>
    </select>
    <label htmlFor="mfa-settings-proof">{t('currentProof')}</label>
    <input id="mfa-settings-proof" autoComplete="one-time-code" required value={proofCode} onChange={(event) => setProofCode(event.target.value)} />
  </>;

  return <main><section aria-labelledby="mfa-settings-title">
    <h1 id="mfa-settings-title">{t('twoFactorSettings')}</h1>
    {status && <p role="status">{t('totpStatus', { state: t(status.totpEnabled ? 'enabled' : 'disabled'), count: status.recoveryCodesRemaining })}</p>}
    {!enrollment && <form onSubmit={start}>
      {proofFields}
      <button type="submit" disabled={busy || !status}>{t(status?.totpEnabled ? 'replaceAuthenticator' : 'setupAuthenticator')}</button>
    </form>}
    {enrollment && <form onSubmit={confirm}>
      <p>{t('enterAuthenticatorSecret')}</p>
      <output aria-label={t('authenticatorSecret')}>{enrollment.base32Secret}</output>
      <p><a href={enrollment.otpauthUri}>{t('openAuthenticator')}</a></p>
      <label htmlFor="mfa-enrollment-code">{t('sixDigitCode')}</label>
      <input id="mfa-enrollment-code" inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6}" maxLength={6} required value={confirmationCode} onChange={(event) => setConfirmationCode(event.target.value)} />
      <button type="submit" disabled={busy}>{t('confirmAuthenticator')}</button>
    </form>}
    {status?.totpEnabled && <div aria-label={t('additionalTwoFactorActions')}>
      <button type="button" disabled={busy || !proofCode} onClick={() => void rotate()}>{t('generateRecoveryCodes')}</button>
      <button type="button" disabled={busy || !proofCode} onClick={() => void disable()}>{t('disableTwoFactor')}</button>
    </div>}
    {recoveryCodes.length > 0 && <section aria-labelledby="recovery-codes-title">
      <h2 id="recovery-codes-title">{t('saveRecoveryCodes')}</h2>
      <p>{t('recoveryCodesWarning')}</p>
      <ol>{recoveryCodes.map((code) => <li key={code}><code>{code}</code></li>)}</ol>
      <button type="button" onClick={() => setRecoveryCodes([])}>{t('recoveryCodesSaved')}</button>
    </section>}
    <p role="alert">{error}</p>
  </section></main>;
}
