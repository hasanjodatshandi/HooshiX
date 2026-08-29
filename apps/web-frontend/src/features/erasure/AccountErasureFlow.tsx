import { type FormEvent, useEffect, useState } from 'react';
import { bffClient, type MfaProof, type MfaStatus } from '../../api/bffClient';
import { getErrorMessage } from '../../errors/getErrorMessage';
import { navigate } from '../../navigation/navigate';
import { accountErased } from '../../state/appActions';
import { useAppState } from '../../state/appState';
import { recoveryCode, totpCode } from '../../validation/userInput';

const CONFIRMATION = 'ERASE_MY_ACCOUNT';

export function AccountErasureFlow() {
  const { dispatch } = useAppState();
  const [status, setStatus] = useState<MfaStatus | null>(null);
  const [confirmation, setConfirmation] = useState('');
  const [proofType, setProofType] = useState<MfaProof['type']>('TOTP');
  const [proofCode, setProofCode] = useState('');
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

  async function erase(event: FormEvent) {
    event.preventDefault();
    if (busy || confirmation !== CONFIRMATION) return;
    setBusy(true);
    setError('');
    try {
      const mfaProof = status?.totpEnabled
        ? {
            type: proofType,
            code: proofType === 'TOTP' ? totpCode(proofCode) : recoveryCode(proofCode),
          }
        : undefined;
      await bffClient.requestSelfErasure({ confirmation: CONFIRMATION, mfaProof });
      dispatch(accountErased());
      navigate('/login');
    } catch (cause) {
      setError(getErrorMessage(cause));
      setBusy(false);
    } finally {
      setProofCode('');
    }
  }

  return <main><section aria-labelledby="account-erasure-title">
    <h1 id="account-erasure-title">Permanently erase account</h1>
    <p>This action has no self-service undo. Leave or transfer every active tenant membership first.</p>
    <p>You must have authenticated within the last five minutes. Acceptance immediately signs out every browser session while each service completes its own irreversible erasure.</p>
    <form onSubmit={(event) => void erase(event)}>
      <label htmlFor="account-erasure-confirmation">Type {CONFIRMATION} to confirm</label>
      <input id="account-erasure-confirmation" autoComplete="off" required value={confirmation} onChange={(event) => setConfirmation(event.target.value)} />
      {status?.totpEnabled && <>
        <label htmlFor="account-erasure-proof-type">Current MFA proof type</label>
        <select id="account-erasure-proof-type" value={proofType} onChange={(event) => { setProofType(event.target.value as MfaProof['type']); setProofCode(''); }}>
          <option value="TOTP">Authenticator code</option>
          <option value="RECOVERY_CODE">Recovery code</option>
        </select>
        <label htmlFor="account-erasure-proof">Current MFA proof</label>
        <input id="account-erasure-proof" autoComplete="one-time-code" required value={proofCode} onChange={(event) => setProofCode(event.target.value)} />
      </>}
      <button type="submit" disabled={busy || !status || confirmation !== CONFIRMATION || Boolean(status.totpEnabled && !proofCode)}>Permanently erase my account</button>
    </form>
    <p role="alert">{error}</p>
  </section></main>;
}
