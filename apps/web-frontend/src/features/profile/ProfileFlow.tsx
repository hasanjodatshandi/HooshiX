import { type FormEvent, useEffect, useState } from 'react';
import { bffClient, type Contact, type Profile } from '../../api/bffClient';
import { getErrorMessage } from '../../errors/getErrorMessage';
import { canonicalEmail, canonicalName, verificationCode } from '../../validation/userInput';

export function ProfileFlow() {
  const [profile, setProfile] = useState<Profile | null>(null);
  const [contacts, setContacts] = useState<Contact[]>([]);
  const [value, setValue] = useState('');
  const [codes, setCodes] = useState<Record<string, string>>({});
  const [firstName, setFirstName] = useState('');
  const [lastName, setLastName] = useState('');
  const [fatherName, setFatherName] = useState('');
  const [error, setError] = useState('');

  async function reload() {
    const [nextProfile, nextContacts] = await Promise.all([bffClient.getProfile(), bffClient.getContacts()]);
    setProfile(nextProfile);
    setFirstName(nextProfile.firstName);
    setLastName(nextProfile.lastName);
    setFatherName(nextProfile.fatherName ?? '');
    setContacts(nextContacts);
  }

  useEffect(() => { void reload().catch((cause) => setError(getErrorMessage(cause))); }, []);

  async function run(action: () => Promise<unknown>) {
    setError('');
    try {
      await action();
      await reload();
    } catch (cause) {
      setError(getErrorMessage(cause));
    }
  }

  async function add(event: FormEvent) {
    event.preventDefault();
    await run(async () => {
      await bffClient.addContact({ type: 'EMAIL', value: canonicalEmail(value), locale: 'fa' });
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

  return <section aria-labelledby="profile-title">
    <h1 id="profile-title">Profile</h1>
    <form onSubmit={(event) => void update(event)}>
      <label htmlFor="profile-first-name">First name</label>
      <input id="profile-first-name" maxLength={240} required value={firstName} onChange={(event) => setFirstName(event.target.value)} />
      <label htmlFor="profile-last-name">Last name</label>
      <input id="profile-last-name" maxLength={240} required value={lastName} onChange={(event) => setLastName(event.target.value)} />
      <label htmlFor="profile-father-name">Father name</label>
      <input id="profile-father-name" maxLength={240} value={fatherName} onChange={(event) => setFatherName(event.target.value)} />
      <button type="submit" disabled={!profile}>Save profile</button>
    </form>
    <h2>Contacts</h2>
    <ul>{contacts.map((contact) => <li key={contact.id}>
      {contact.type}: {contact.value} {contact.primary ? '(primary)' : ''}
      {!contact.verified && <>
        <label htmlFor={`code-${contact.id}`}>Verification code</label>
        <input id={`code-${contact.id}`} inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{8}" maxLength={8} value={codes[contact.id] ?? ''} onChange={(event) => setCodes((old) => ({ ...old, [contact.id]: event.target.value }))} />
        <button type="button" onClick={() => void run(() => bffClient.verifyContact(contact.id, verificationCode(codes[contact.id] ?? '')))}>Verify</button>
        <button type="button" onClick={() => void run(() => bffClient.resendContactVerification(contact.id))}>Resend</button>
      </>}
      {contact.verified && !contact.primary && <button type="button" onClick={() => void run(() => bffClient.setPrimaryContact(contact.id))}>Primary</button>}
      {!contact.primary && <button type="button" onClick={() => void run(() => bffClient.removeContact(contact.id))}>Remove</button>}
    </li>)}</ul>
    <form onSubmit={(event) => void add(event)}>
      <label htmlFor="new-contact">Email</label>
      <input id="new-contact" type="email" autoComplete="email" maxLength={254} required value={value} onChange={(event) => setValue(event.target.value)} />
      <button type="submit">Add contact</button>
    </form>
    <p role="alert">{error}</p>
  </section>;
}
