import { useEffect, useState } from 'react';
import { bffClient, type Contact, type Profile } from '../../api/bffClient';

export function ProfileFlow() {
  const [profile, setProfile] = useState<Profile | null>(null);
  const [contacts, setContacts] = useState<Contact[]>([]);
  const [value, setValue] = useState('');

  async function reload() {
    const [p, c] = await Promise.all([bffClient.getProfile(), bffClient.getContacts()]);
    setProfile(p);
    setContacts(c);
  }

  useEffect(() => { void reload(); }, []);

  async function add() {
    await bffClient.addContact({ type: 'EMAIL', value });
    setValue('');
    await reload();
  }

  async function makePrimary(id: string) {
    await bffClient.setPrimaryContact(id);
    await reload();
  }

  async function remove(id: string) {
    await bffClient.removeContact(id);
    await reload();
  }

  return <section>
    <h1>Profile</h1>
    <p>{profile?.firstName} {profile?.lastName}</p>
    <h2>Contacts</h2>
    <ul>{contacts.map((c) => <li key={c.id}>{c.type}: {c.value} {c.primary ? '(primary)' : ''} <button onClick={() => void makePrimary(c.id)}>Primary</button> <button onClick={() => void remove(c.id)}>Remove</button></li>)}</ul>
    <input value={value} onChange={(e) => setValue(e.target.value)} />
    <button onClick={() => void add()}>Add contact</button>
  </section>;
}
