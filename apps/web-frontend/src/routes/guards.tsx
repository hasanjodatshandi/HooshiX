import { type ReactNode, useEffect } from 'react';
import { useAppState } from '../state/appState';
import { navigate } from '../navigation/navigate';
import { canEnterStage } from '../state/flow';

export function VerificationGuard({ children }: { children: ReactNode }) {
  const { state } = useAppState();

  if (!canEnterStage('verification', { contact: state.contact, authenticated: false, tenantId: null })) {
    return <Redirect to="/" />;
  }

  return children;
}

export function AuthenticatedGuard({ children }: { children: ReactNode }) {
  const { state } = useAppState();

  if (state.sessionStatus === 'checking') {
    return <main aria-busy="true"><p role="status">Checking session…</p></main>;
  }

  if (!canEnterStage('authenticated', { contact: '', authenticated: state.authenticated, tenantId: null })) {
    return <Redirect to="/login" />;
  }

  return children;
}

function Redirect({ to }: { to: string }) {
  useEffect(() => navigate(to), [to]);
  return null;
}
