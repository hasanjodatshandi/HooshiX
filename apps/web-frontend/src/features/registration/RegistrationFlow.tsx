import { type FormEvent, useState } from 'react';
import { bffClient } from '../../api/bffClient';
import { useAppState } from '../../state/appState';
import { getErrorMessage } from '../../errors/getErrorMessage';
import { navigate } from '../../navigation/navigate';
import * as actions from '../../state/appActions';
import { canonicalEmail, canonicalName, normalizedNewPassword } from '../../validation/userInput';
import { useI18n } from '../../i18n/I18nProvider';

export function RegistrationFlow() {
  const { locale, t } = useI18n();
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
    if (status === 'loading') return;
    dispatch(actions.clearError());
    dispatch(actions.registrationRequestStarted());
    try {
      const safeContact = canonicalEmail(contact);
      await bffClient.register({
        channel: 'EMAIL',
        contact: safeContact,
        password: normalizedNewPassword(password),
        locale,
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
    } finally {
      setPassword('');
    }
  }

  return <section aria-labelledby="registration-title">
    <h2 id="registration-title">{t('registration')}</h2>
    <form onSubmit={submit}>
      <label htmlFor="registration-contact">{t('email')}</label>
      <input id="registration-contact" type="email" autoComplete="email" maxLength={254} required value={contact} onChange={(event) => setContact(event.target.value)} />
      <label htmlFor="registration-first-name">{t('firstName')}</label>
      <input id="registration-first-name" autoComplete="given-name" maxLength={240} required value={firstName} onChange={(event) => setFirstName(event.target.value)} />
      <label htmlFor="registration-last-name">{t('lastName')}</label>
      <input id="registration-last-name" autoComplete="family-name" maxLength={240} required value={lastName} onChange={(event) => setLastName(event.target.value)} />
      <label htmlFor="registration-father-name">{t('fatherName')}</label>
      <input id="registration-father-name" maxLength={240} value={fatherName} onChange={(event) => setFatherName(event.target.value)} />
      <label htmlFor="registration-password">{t('password')}</label>
      <input id="registration-password" type="password" autoComplete="new-password" minLength={12} maxLength={256} required value={password} onChange={(event) => setPassword(event.target.value)} />
      <button type="submit" disabled={status === 'loading'}>{t('continue')}</button>
    </form>
    <p role="status">{status === 'loading' ? t('submitting') : status === 'success' ? t('completed') : ''}</p>
    <p role="alert">{error}</p>
  </section>;
}
