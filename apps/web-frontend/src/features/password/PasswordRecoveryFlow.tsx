import { useState } from 'react';
import { bffClient } from '../../api/bffClient';

export function PasswordRecoveryFlow() {
 const [contact,setContact]=useState(''); const [code,setCode]=useState(''); const [password,setPassword]=useState(''); const [sent,setSent]=useState(false);
 return <section><h1>Password recovery</h1><input value={contact} onChange={e=>setContact(e.target.value)} placeholder="contact"/><button onClick={()=>void bffClient.requestPasswordRecovery(contact).then(()=>setSent(true))}>Request</button>{sent && <><input value={code} onChange={e=>setCode(e.target.value)} placeholder="code"/><input value={password} onChange={e=>setPassword(e.target.value)} placeholder="new password"/><button onClick={()=>void bffClient.confirmPasswordRecovery({contact,code,newPassword:password})}>Confirm</button></>}</section>;
}
