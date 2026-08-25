import { type FormEvent, useState } from 'react';
import { bffClient } from '../../api/bffClient';
import { useAppState } from '../../state/appState';
import { getErrorMessage } from '../../errors/getErrorMessage';
import { navigate } from '../../navigation/navigate';
import * as actions from '../../state/appActions';
import { canonicalEmail, canonicalName, normalizedNewPassword } from '../../validation/userInput';

export function RegistrationFlow() {
  const { state: appState, dispatch } = useAppState();
  const [contact, setContact] = useState(appState.contact);
  const [password, setPassword] = useState('');
  const [firstName, setFirstName] = useState('');
  const [lastName, setLastName] = useState('');
  const [fatherName, setFatherName] = useState('');
  const status = appState.registrationStatus;
  const error = appState.lastError ?? '';

  async function submit(event: FormEvent) {
    event.preventDefault();
    dispatch(actions.clearError());
    dispatch(actions.registrationRequestStarted());
    try {
      const safeContact = canonicalEmail(contact);
      await bffClient.register({
        channel: 'EMAIL',
        contact: safeContact,
        password: normalizedNewPassword(password),
        locale: 'fa',
        firstName: canonicalName(firstName)!,
        lastName: canonicalName(lastName)!,
        fatherName: canonicalName(fatherName, false),
      });
      dispatch(actions.registrationStarted(safeContact));
      dispatch(actions.registrationSucceeded());
      setPassword('');
      navigate('/verify');
    } catch (cause) {
      dispatch(actions.registrationFailed(getErrorMessage(cause)));
    }
  }

  return <section aria-labelledby="registration-title">
    <h2 id="registration-title">Registration</h2>
    <form onSubmit={submit}>
      <label htmlFor="registration-contact">Email</label>
      <input id="registration-contact" type="email" autoComplete="email" maxLength={254} required value={contact} onChange={(event) => setContact(event.target.value)} />
      <label htmlFor="registration-first-name">First name</label>
      <input id="registration-first-name" autoComplete="given-name" maxLength={240} required value={firstName} onChange={(event) => setFirstName(event.target.value)} />
      <label htmlFor="registration-last-name">Last name</label>
      <input id="registration-last-name" autoComplete="family-name" maxLength={240} required value={lastName} onChange={(event) => setLastName(event.target.value)} />
      <label htmlFor="registration-father-name">Father name</label>
      <input id="registration-father-name" maxLength={240} value={fatherName} onChange={(event) => setFatherName(event.target.value)} />
      <label htmlFor="registration-password">Password</label>
      <input id="registration-password" type="password" autoComplete="new-password" minLength={12} maxLength={256} required value={password} onChange={(event) => setPassword(event.target.value)} />
      <button type="submit">Continue</button>
    </form>
    <p role="status">{status}</p>
    <p role="alert">{error}</p>
  </section>;
}
