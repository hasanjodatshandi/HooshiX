import { useEffect, useState } from 'react';
import { bffClient } from '../api/bffClient';
import { getErrorMessage } from '../errors/getErrorMessage';
import { navigate } from '../navigation/navigate';
import { routes } from '../routes/routes';
import { useAppState } from '../state/appState';
import * as actions from '../state/appActions';
import { useI18n } from '../i18n/I18nProvider';

export function OidcCompletionPage() {
  const { t } = useI18n();
  const { dispatch } = useAppState();
  const [error, setError] = useState('');

  useEffect(() => {
    const controller = new AbortController();
    void bffClient.getSessionState({ signal: controller.signal }).then((session) => {
      if (session.mode === 'MFA_PREAUTH') {
        navigate(routes.loginMfa);
        return;
      }
      if (!session.authenticated) throw new Error('OIDC session was not established');
      dispatch(actions.loginSucceeded(session.tenantSelected));
      navigate(session.tenantSelected ? routes.application : routes.profile);
    }).catch((cause) => {
      if (!controller.signal.aborted) setError(getErrorMessage(cause));
    });
    return () => controller.abort();
  }, [dispatch]);

  return <main aria-busy={!error}><h1>{t('completingSignIn')}</h1><p role="status">{t('verifyingSecureSession')}</p><p role="alert">{error}</p></main>;
}
