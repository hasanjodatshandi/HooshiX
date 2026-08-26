import { useEffect, useState } from 'react';
import { bffClient } from '../api/bffClient';
import { getErrorMessage } from '../errors/getErrorMessage';
import { navigate } from '../navigation/navigate';
import { routes } from '../routes/routes';
import { useAppState } from '../state/appState';
import * as actions from '../state/appActions';

export function OidcCompletionPage() {
  const { dispatch } = useAppState();
  const [error, setError] = useState('');

  useEffect(() => {
    void bffClient.getSessionState().then((session) => {
      if (session.mode === 'MFA_PREAUTH') {
        navigate(routes.loginMfa);
        return;
      }
      if (!session.authenticated) throw new Error('OIDC session was not established');
      dispatch(actions.loginSucceeded());
      navigate(session.tenantSelected ? routes.application : routes.profile);
    }).catch((cause) => setError(getErrorMessage(cause)));
  }, [dispatch]);

  return <main aria-busy={!error}><h1>Completing sign in</h1><p role="status">Verifying your secure session…</p><p role="alert">{error}</p></main>;
}
