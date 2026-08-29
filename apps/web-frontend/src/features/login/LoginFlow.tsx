import { type FormEvent, useState } from 'react';
import { bffClient } from '../../api/bffClient';
import { useAppState } from '../../state/appState';
import { getErrorMessage } from '../../errors/getErrorMessage';
import * as actions from '../../state/appActions';
import { canonicalEmail, normalizedCurrentPassword } from '../../validation/userInput';
import { navigate } from '../../navigation/navigate';
import { routes } from '../../routes/routes';

export function LoginFlow() {
  const { dispatch } = useAppState();
  const [contact, setContact] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (busy) return;
    setBusy(true);
    setError('');
    try {
      const session = await bffClient.login({
        contact: canonicalEmail(contact),
        password: normalizedCurrentPassword(password),
      });
      setPassword('');
      if (session.mode === 'MFA_PREAUTH') {
        navigate(routes.loginMfa);
        return;
      }
      dispatch(actions.loginSucceeded(session.mode === 'TENANT_AUTHENTICATED'));
    } catch (cause) {
      setError(getErrorMessage(cause));
    } finally {
      setPassword('');
      setBusy(false);
    }
  }

  async function googleLogin() {
    if (busy) return;
    setBusy(true);
    setError('');
    try {
      const authorization = await bffClient.startGoogleLogin();
      window.location.assign(authorization.authorizationUrl);
    } catch (cause) {
      setError(getErrorMessage(cause));
      setBusy(false);
    }
  }

  return <section aria-labelledby="login-title">
    <h2 id="login-title">Login</h2>
    <form onSubmit={submit}>
      <label htmlFor="login-email">Email</label>
      <input id="login-email" value={contact} onChange={(event) => setContact(event.target.value)} type="email" autoComplete="email" maxLength={254} required />
      <label htmlFor="login-password">Password</label>
      <input id="login-password" value={password} onChange={(event) => setPassword(event.target.value)} type="password" autoComplete="current-password" required />
      <button type="submit" disabled={busy}>Continue</button>
    </form>
    <p aria-hidden="true">or</p>
    <button type="button" disabled={busy} onClick={() => void googleLogin()}>Continue with Google</button>
    <p role="alert">{error}</p>
  </section>;
}
