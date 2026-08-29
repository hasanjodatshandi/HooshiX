import { type FormEvent, useEffect, useState } from 'react';
import { bffClient, type Contact, type Profile } from '../../api/bffClient';
import { getErrorMessage } from '../../errors/getErrorMessage';
import { InternalLink } from '../../navigation/InternalLink';
import { routes } from '../../routes/routes';
import { canonicalEmail, canonicalName, verificationCode } from '../../validation/userInput';
import { useI18n } from '../../i18n/I18nProvider';

export function ProfileFlow() {
  const { locale, t } = useI18n();
  const [profile, setProfile] = useState<Profile | null>(null);
  const [contacts, setContacts] = useState<Contact[]>([]);
  const [value, setValue] = useState('');
  const [codes, setCodes] = useState<Record<string, string>>({});
  const [firstName, setFirstName] = useState('');
  const [lastName, setLastName] = useState('');
  const [fatherName, setFatherName] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  async function reload(signal?: AbortSignal) {
    const options = signal ? { signal } : {};
    const [nextProfile, nextContacts] = await Promise.all([
      bffClient.getProfile(options),
      bffClient.getContacts(options),
    ]);
    setProfile(nextProfile);
    setFirstName(nextProfile.firstName);
    setLastName(nextProfile.lastName);
    setFatherName(nextProfile.fatherName ?? '');
    setContacts(nextContacts);
  }

  useEffect(() => {
    const controller = new AbortController();
    void reload(controller.signal).catch((cause) => {
      if (!controller.signal.aborted) setError(getErrorMessage(cause));
    });
    return () => controller.abort();
  }, []);

  async function run(action: () => Promise<unknown>) {
    if (busy) return;
    setBusy(true);
    setError('');
    try {
      await action();
      await reload();
    } catch (cause) {
      setError(getErrorMessage(cause));
    } finally {
      setCodes({});
      setBusy(false);
    }
  }

  async function add(event: FormEvent) {
    event.preventDefault();
    await run(async () => {
      await bffClient.addContact({ type: 'EMAIL', value: canonicalEmail(value), locale });
      setValue('');
    });
  }

  async function update(event: FormEvent) {
    event.preventDefault();
    await run(() => bffClient.updateProfile({
      firstName: canonicalName(firstName)!,
      lastName: canonicalName(lastName)!,
      fatherName: canonicalName(fatherName, false),
    }));
  }

  const onboardingComplete =
    Boolean(profile?.firstName.trim() && profile?.lastName.trim())
    && contacts.some((contact) => contact.verified);

  return <section aria-labelledby="profile-title">
    <h1 id="profile-title">{t('profile')}</h1>
    <form onSubmit={(event) => void update(event)}>
      <label htmlFor="profile-first-name">{t('firstName')}</label>
      <input id="profile-first-name" maxLength={240} required value={firstName} onChange={(event) => setFirstName(event.target.value)} />
      <label htmlFor="profile-last-name">{t('lastName')}</label>
      <input id="profile-last-name" maxLength={240} required value={lastName} onChange={(event) => setLastName(event.target.value)} />
      <label htmlFor="profile-father-name">{t('fatherName')}</label>
      <input id="profile-father-name" maxLength={240} value={fatherName} onChange={(event) => setFatherName(event.target.value)} />
      <button type="submit" disabled={busy || !profile}>{t('saveProfile')}</button>
    </form>
    <h2>{t('contacts')}</h2>
    <ul>{contacts.map((contact) => <li key={contact.id}>
      {contact.type}: {contact.value} {contact.primary ? t('contactPrimary') : ''}
      {!contact.verified && <>
        <label htmlFor={`code-${contact.id}`}>{t('verificationCode')}</label>
        <input id={`code-${contact.id}`} inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{8}" maxLength={8} value={codes[contact.id] ?? ''} onChange={(event) => setCodes((old) => ({ ...old, [contact.id]: event.target.value }))} />
        <button type="button" disabled={busy} onClick={() => void run(() => bffClient.verifyContact(contact.id, verificationCode(codes[contact.id] ?? '')))}>{t('verify')}</button>
        <button type="button" disabled={busy} onClick={() => void run(() => bffClient.resendContactVerification(contact.id))}>{t('resend')}</button>
      </>}
      {contact.verified && !contact.primary && <button type="button" disabled={busy} onClick={() => void run(() => bffClient.setPrimaryContact(contact.id))}>{t('primary')}</button>}
      {!contact.primary && <button type="button" disabled={busy} onClick={() => void run(() => bffClient.removeContact(contact.id))}>{t('remove')}</button>}
    </li>)}</ul>
    <form onSubmit={(event) => void add(event)}>
      <label htmlFor="new-contact">{t('email')}</label>
      <input id="new-contact" type="email" autoComplete="email" maxLength={254} required value={value} onChange={(event) => setValue(event.target.value)} />
      <button type="submit" disabled={busy}>{t('addContact')}</button>
    </form>
    {onboardingComplete && <p><InternalLink to={routes.tenantSelection}>{t('continueToTenantSelection')}</InternalLink></p>}
    <p role="alert">{error}</p>
  </section>;
}
