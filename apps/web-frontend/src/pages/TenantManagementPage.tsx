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
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');

  const reload = useCallback(async (signal?: AbortSignal) => {
    const options = signal ? { signal } : {};
    const inbox = await bffClient.listReceivedInvitations(options);
    setReceived(inbox.invitations);
    if (state.selectedTenantId) {
      const tenant = await bffClient.listTenantInvitations(options);
      setManaged(tenant.invitations);
    } else {
      setManaged([]);
    }
  }, [state.selectedTenantId]);

  useEffect(() => {
    const controller = new AbortController();
    void reload(controller.signal).catch((error: unknown) => {
      if (!controller.signal.aborted) setMessage(getErrorMessage(error));
    });
    return () => controller.abort();
  }, [reload]);

  async function lifecycle(operation: 'suspend' | 'resume' | 'restore' | 'delete') {
    if (busy) return;
    setBusy(true);
    setMessage('');
    try {
      const value = await bffClient[`${operation}Tenant`](tenantId);
      setResult(value);
      if (operation === 'delete') dispatch(actions.tenantCleared());
    } catch (error: unknown) {
      setMessage(getErrorMessage(error));
    } finally {
      setBusy(false);
    }
  }

  async function mutate(
    invitationId: string,
    operation: 'accept' | 'decline' | 'revoke' | 'reissue',
  ) {
    if (busy) return;
    setBusy(true);
    setMessage('');
    try {
      await bffClient[`${operation}Invitation`](invitationId);
      await reload();
    } catch (error: unknown) {
      setMessage(getErrorMessage(error));
    } finally {
      setBusy(false);
    }
  }

  return <main aria-labelledby="tenant-management-title">
    <h1 id="tenant-management-title">Tenant management</h1>
    <section aria-labelledby="tenant-lifecycle-title">
      <h2 id="tenant-lifecycle-title">Lifecycle</h2>
      <label>Tenant ID<input value={tenantId} onChange={(event) => setTenantId(event.target.value)} /></label>
      <button disabled={busy || !tenantId} onClick={() => void lifecycle('suspend')}>Suspend</button>
      <button disabled={busy || !tenantId} onClick={() => void lifecycle('resume')}>Resume</button>
      <button disabled={busy || !tenantId} onClick={() => void lifecycle('restore')}>Restore</button>
      <button disabled={busy || !state.selectedTenantId || tenantId !== state.selectedTenantId} onClick={() => void lifecycle('delete')}>Delete selected tenant</button>
      {result && <p role="status">{result.lifecycle} → {result.targetLifecycle}{result.pending ? ' (pending)' : ''}</p>}
    </section>
    <InvitationSection title="Received invitations" values={received} actions={['accept', 'decline']} busy={busy} mutate={mutate} />
    {state.selectedTenantId && <InvitationSection title="Selected tenant invitations" values={managed} actions={['revoke', 'reissue']} busy={busy} mutate={mutate} />}
    {message && <p role="alert">{message}</p>}
  </main>;
}

function InvitationSection({
  title,
  values,
  actions,
  busy,
  mutate,
}: {
  title: string;
  values: InvitationSummary[];
  actions: Array<'accept' | 'decline' | 'revoke' | 'reissue'>;
  busy: boolean;
  mutate: (id: string, operation: 'accept' | 'decline' | 'revoke' | 'reissue') => Promise<void>;
}) {
  return <section aria-label={title}>
    <h2>{title}</h2>
    {values.length === 0 ? <p>No invitations</p> : <ul>{values.map((invitation) => <li key={invitation.invitationId}>
      <span>{invitation.tenantName} — {invitation.state} — {new Date(invitation.expiresAt).toLocaleString()}</span>
      {actions.map((operation) => <button key={operation} disabled={busy || (operation !== 'reissue' && invitation.state !== 'PENDING')} onClick={() => void mutate(invitation.invitationId, operation)}>{operation}</button>)}
    </li>)}</ul>}
  </section>;
}
