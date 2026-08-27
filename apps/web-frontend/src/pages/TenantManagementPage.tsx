import { useCallback, useEffect, useState } from 'react';
import {
  bffClient,
  type InvitationSummary,
  type TenantLifecycleResult,
} from '../api/bffClient';
import { getErrorMessage } from '../errors/getErrorMessage';
import { useAppState } from '../state/appState';
import * as actions from '../state/appActions';

export function TenantManagementPage() {
  const { state, dispatch } = useAppState();
  const [tenantId, setTenantId] = useState(state.selectedTenantId ?? '');
  const [received, setReceived] = useState<InvitationSummary[]>([]);
  const [managed, setManaged] = useState<InvitationSummary[]>([]);
  const [result, setResult] = useState<TenantLifecycleResult | null>(null);
  const [message, setMessage] = useState('');

  const reload = useCallback(async () => {
    const inbox = await bffClient.listReceivedInvitations();
    setReceived(inbox.invitations);
    if (state.selectedTenantId) {
      const tenant = await bffClient.listTenantInvitations();
      setManaged(tenant.invitations);
    } else {
      setManaged([]);
    }
  }, [state.selectedTenantId]);

  useEffect(() => {
    reload().catch((error: unknown) => setMessage(getErrorMessage(error)));
  }, [reload]);

  async function lifecycle(operation: 'suspend' | 'resume' | 'restore' | 'delete') {
    setMessage('');
    try {
      const value = await bffClient[`${operation}Tenant`](tenantId);
      setResult(value);
      if (operation === 'delete') dispatch(actions.tenantCleared());
    } catch (error: unknown) {
      setMessage(getErrorMessage(error));
    }
  }

  async function mutate(
    invitationId: string,
    operation: 'accept' | 'decline' | 'revoke' | 'reissue',
  ) {
    setMessage('');
    try {
      await bffClient[`${operation}Invitation`](invitationId);
      await reload();
    } catch (error: unknown) {
      setMessage(getErrorMessage(error));
    }
  }

  return <main aria-labelledby="tenant-management-title">
    <h1 id="tenant-management-title">Tenant management</h1>
    <section aria-labelledby="tenant-lifecycle-title">
      <h2 id="tenant-lifecycle-title">Lifecycle</h2>
      <label>Tenant ID<input value={tenantId} onChange={(event) => setTenantId(event.target.value)} /></label>
      <button disabled={!tenantId} onClick={() => lifecycle('suspend')}>Suspend</button>
      <button disabled={!tenantId} onClick={() => lifecycle('resume')}>Resume</button>
      <button disabled={!tenantId} onClick={() => lifecycle('restore')}>Restore</button>
      <button disabled={!state.selectedTenantId || tenantId !== state.selectedTenantId} onClick={() => lifecycle('delete')}>Delete selected tenant</button>
      {result && <p role="status">{result.lifecycle} → {result.targetLifecycle}{result.pending ? ' (pending)' : ''}</p>}
    </section>
    <InvitationSection title="Received invitations" values={received} actions={['accept', 'decline']} mutate={mutate} />
    {state.selectedTenantId && <InvitationSection title="Selected tenant invitations" values={managed} actions={['revoke', 'reissue']} mutate={mutate} />}
    {message && <p role="alert">{message}</p>}
  </main>;
}

function InvitationSection({
  title,
  values,
  actions,
  mutate,
}: {
  title: string;
  values: InvitationSummary[];
  actions: Array<'accept' | 'decline' | 'revoke' | 'reissue'>;
  mutate: (id: string, operation: 'accept' | 'decline' | 'revoke' | 'reissue') => Promise<void>;
}) {
  return <section aria-label={title}>
    <h2>{title}</h2>
    {values.length === 0 ? <p>No invitations</p> : <ul>{values.map((invitation) => <li key={invitation.invitationId}>
      <span>{invitation.tenantName} — {invitation.state} — {new Date(invitation.expiresAt).toLocaleString()}</span>
      {actions.map((operation) => <button key={operation} disabled={operation !== 'reissue' && invitation.state !== 'PENDING'} onClick={() => mutate(invitation.invitationId, operation)}>{operation}</button>)}
    </li>)}</ul>}
  </section>;
}
