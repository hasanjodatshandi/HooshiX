import { useState } from 'react';
import { bffClient } from '../../api/bffClient';
import { useAppState } from '../../state/appState';
import { getErrorMessage } from '../../errors/getErrorMessage';
import { navigate } from '../../navigation/navigate';
import * as actions from '../../state/appActions';

export function VerificationFlow() {
  const [code, setCode] = useState('');

  const { state: appState, dispatch } = useAppState();
  const contact = appState.contact;
  const state = appState.verificationStatus;
  const error = appState.lastError ?? '';

  async function confirm() {
    dispatch(actions.clearError());
    dispatch(actions.verificationRequestStarted());
    try {
      await bffClient.confirm({
      channel: 'EMAIL',
      contact,
      code,
      });
    dispatch(actions.verificationCompleted());

    navigate('/application');
    } catch (e) {
      dispatch(actions.verificationFailed(getErrorMessage(e)));
    }
  }

  return <section aria-labelledby="verification-title"><h2 id="verification-title">Verification</h2><label htmlFor="verification-code">Code</label><input id="verification-code" aria-label="Verification code" value={code} onChange={(e) => setCode(e.target.value)} /><button type="button" onClick={confirm}>Confirm</button><p>{state}</p><p>{error}</p></section>;
}
