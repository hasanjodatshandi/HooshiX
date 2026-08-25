import { type FormEvent, useState } from 'react';
import { bffClient, type MfaProof } from '../../api/bffClient';
import { getErrorMessage } from '../../errors/getErrorMessage';
import { navigate } from '../../navigation/navigate';
import { routes } from '../../routes/routes';
import * as actions from '../../state/appActions';
import { useAppState } from '../../state/appState';
import { recoveryCode, totpCode } from '../../validation/userInput';

export function MfaLoginFlow() {
  const { dispatch } = useAppState();
  const [type, setType] = useState<MfaProof['type']>('TOTP');
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError('');
    try {
      const canonicalCode = type === 'TOTP' ? totpCode(code) : recoveryCode(code);
      const session = await bffClient.completeMfaAuthentication({ type, code: canonicalCode });
      setCode('');
      dispatch(actions.loginSucceeded());
      navigate(session.mode === 'TENANT_AUTHENTICATED' ? routes.application : routes.tenantSelection);
    } catch (cause) {
      setError(getErrorMessage(cause));
    } finally {
      setBusy(false);
    }
  }

  return <main><section aria-labelledby="mfa-login-title">
    <h1 id="mfa-login-title">Two-factor authentication</h1>
    <form onSubmit={submit}>
      <label htmlFor="mfa-login-type">Proof type</label>
      <select id="mfa-login-type" value={type} onChange={(event) => { setType(event.target.value as MfaProof['type']); setCode(''); }}>
        <option value="TOTP">Authenticator code</option>
        <option value="RECOVERY_CODE">Recovery code</option>
      </select>
      <label htmlFor="mfa-login-code">{type === 'TOTP' ? 'Six-digit code' : 'Recovery code'}</label>
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
      <button type="submit" disabled={busy}>Verify</button>
    </form>
    <p role="alert">{error}</p>
  </section></main>;
}
