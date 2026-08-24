import { useState } from 'react';
import { bffClient } from '../../api/bffClient';
import { useAppState } from '../../state/appState';
import * as actions from '../../state/appActions';

export function LoginFlow() {
 const {dispatch}=useAppState();
 const [contact,setContact]=useState('');
 const [password,setPassword]=useState('');
 async function submit(){
  await bffClient.login({contact,password});
  dispatch(actions.loginSucceeded());
 }
 return <section aria-labelledby="login-title"><h2 id="login-title">Login</h2><label>Email<input value={contact} onChange={e=>setContact(e.target.value)} type="email" /></label><label>Password<input value={password} onChange={e=>setPassword(e.target.value)} type="password" /></label><button onClick={submit}>Continue</button></section>;
}
