import { useEffect, useState } from 'react';
import { bffClient, type TenantChoice } from '../api/bffClient';
import { useAppState } from '../state/appState';
import * as actions from '../state/appActions';

export function TenantSelectionPage() {
 const {dispatch}=useAppState();
 const [tenants,setTenants]=useState<TenantChoice[]>([]);
 useEffect(()=>{ bffClient.listTenants().then(x=>setTenants(x.tenants)); },[]);
 async function select(t: TenantChoice){
  await bffClient.selectTenant(t.membershipId);
  dispatch(actions.tenantSelected(t.tenantId));
 }
 return <main><h1>Tenant Selection</h1>{tenants.map(t=><button key={t.membershipId} onClick={()=>select(t)}>{t.name}</button>)}</main>;
}
