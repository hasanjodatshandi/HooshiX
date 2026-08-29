import { useEffect, useState } from 'react';
import { bffClient, type ExternalIdentityStatus } from '../../api/bffClient';
import { getErrorMessage } from '../../errors/getErrorMessage';

export function ExternalIdentitySettingsFlow() {
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
    <h1 id="external-identities-title">External identities</h1>
    {status && <p role="status">Google is {status.googleLinked ? 'linked' : 'not linked'}.</p>}
    {status?.googleLinked
      ? <button type="button" disabled={busy} onClick={() => void unlink()}>Unlink Google</button>
      : <button type="button" disabled={busy || !status} onClick={() => void link()}>Link Google</button>}
    <p>Linking or unlinking requires a recent sign-in. Email equality never links accounts automatically.</p>
    <p role="alert">{error}</p>
  </section></main>;
}
