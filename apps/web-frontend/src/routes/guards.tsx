import type { ReactNode } from 'react';
import { useAppState } from '../state/appState';
import { navigate } from '../navigation/navigate';
import { canEnterStage } from '../state/flow';

export function VerificationGuard({ children }: { children: ReactNode }) {
  const { state } = useAppState();

  if (!canEnterStage('verification', { contact: state.contact, authenticated: false, tenantId: null })) {
    navigate('/');
    return null;
  }

  return children;
}

export function AuthenticatedGuard({ children }: { children: ReactNode }) {
  const { state } = useAppState();

  if (!canEnterStage('authenticated', { contact: '', authenticated: state.authenticated, tenantId: null })) {
    navigate('/');
    return null;
  }

  return children;
}
