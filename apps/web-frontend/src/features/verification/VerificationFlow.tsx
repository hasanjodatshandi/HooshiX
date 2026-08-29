import { type FormEvent, useState } from 'react';
import { bffClient } from '../../api/bffClient';
import { useAppState } from '../../state/appState';
import { getErrorMessage } from '../../errors/getErrorMessage';
import { navigate } from '../../navigation/navigate';
import * as actions from '../../state/appActions';
import { canonicalEmail, verificationCode } from '../../validation/userInput';

export function VerificationFlow() {
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

  return <section aria-labelledby="verification-title">
    <h2 id="verification-title">Verification</h2>
    <form onSubmit={(event) => void confirm(event)}>
      <label htmlFor="verification-code">Code</label>
      <input id="verification-code" aria-label="Verification code" inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{8}" maxLength={8} required value={code} onChange={(event) => setCode(event.target.value)} />
      <button type="submit" disabled={status === 'loading'}>Confirm</button>
    </form>
    <p role="status">{status}</p>
    <p role="alert">{error}</p>
  </section>;
}
