import { useEffect, useState } from 'react';
import { bffClient, type ExternalIdentityStatus } from '../../api/bffClient';
import { getErrorMessage } from '../../errors/getErrorMessage';
import { useI18n } from '../../i18n/I18nProvider';

export function ExternalIdentitySettingsFlow() {
  const { t } = useI18n();
  const [status, setStatus] = useState<ExternalIdentityStatus | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    const controller = new AbortController();
    void bffClient.getExternalIdentityStatus({ signal: controller.signal })
      .then(setStatus)
      .catch((cause) => {
        if (!controller.signal.aborted) setError(getErrorMessage(cause));
      });
    return () => controller.abort();
  }, []);

  async function link() {
    if (busy) return;
    setBusy(true);
    setError('');
    try {
      const authorization = await bffClient.startGoogleLink();
      window.location.assign(authorization.authorizationUrl);
    } catch (cause) {
      setError(getErrorMessage(cause));
      setBusy(false);
    }
  }

  async function unlink() {
    if (busy) return;
    setBusy(true);
    setError('');
    try {
      await bffClient.unlinkGoogleIdentity();
      setStatus({ googleLinked: false });
    } catch (cause) {
      setError(getErrorMessage(cause));
    } finally {
      setBusy(false);
    }
  }

  return <main><section aria-labelledby="external-identities-title">
    <h1 id="external-identities-title">{t('externalIdentities')}</h1>
    {status && <p role="status">{t('googleLinkStatus', { state: t(status.googleLinked ? 'linked' : 'notLinked') })}</p>}
    {status?.googleLinked
      ? <button type="button" disabled={busy} onClick={() => void unlink()}>{t('unlinkGoogle')}</button>
      : <button type="button" disabled={busy || !status} onClick={() => void link()}>{t('linkGoogle')}</button>}
    <p>{t('externalIdentityWarning')}</p>
    <p role="alert">{error}</p>
  </section></main>;
}
