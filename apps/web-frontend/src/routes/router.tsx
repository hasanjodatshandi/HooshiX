import { ApplicationShell } from '../app/ApplicationShell';
import { ApplicationPage } from '../pages/ApplicationPage';
import { LoginPage } from '../pages/LoginPage';
import { TenantSelectionPage } from '../pages/TenantSelectionPage';
import { TenantManagementPage } from '../pages/TenantManagementPage';
import { VerificationFlow } from '../features/verification/VerificationFlow';
import { ProfileFlow } from '../features/profile/ProfileFlow';
import { ChangePasswordFlow } from '../features/password/ChangePasswordFlow';
import { PasswordRecoveryFlow } from '../features/password/PasswordRecoveryFlow';
import { MfaLoginFlow } from '../features/mfa/MfaLoginFlow';
import { MfaSettingsFlow } from '../features/mfa/MfaSettingsFlow';
import { ExternalIdentitySettingsFlow } from '../features/externalIdentity/ExternalIdentitySettingsFlow';
import { AccountErasureFlow } from '../features/erasure/AccountErasureFlow';
import { OidcCompletionPage } from '../pages/OidcCompletionPage';
import { VerificationGuard, AuthenticatedGuard } from './guards';
import { routes } from './routes';

import { usePath } from '../navigation/usePath';
import { useEffect } from 'react';
import { registrationCleared } from '../state/appActions';
import { useAppState } from '../state/appState';

export function Router() {
  const path = usePath();
  const { dispatch } = useAppState();

  useEffect(() => {
    if (path !== routes.register && path !== routes.verify) {
      dispatch(registrationCleared());
    }
  }, [dispatch, path]);

  if (path === routes.verify) {
    return <VerificationGuard><VerificationFlow /></VerificationGuard>;
  }

  if (path === routes.login) {
    return <LoginPage />;
  }

  if (path === routes.loginMfa) {
    return <MfaLoginFlow />;
  }

  if (path === routes.oidcComplete) {
    return <OidcCompletionPage />;
  }

  if (path === routes.tenantSelection) {
    return <AuthenticatedGuard><TenantSelectionPage /></AuthenticatedGuard>;
  }

  if (path === routes.tenantManagement) {
    return <AuthenticatedGuard><TenantManagementPage /></AuthenticatedGuard>;
  }

  if (path === routes.profile) {
    return <AuthenticatedGuard><ProfileFlow /></AuthenticatedGuard>;
  }

  if (path === routes.passwordRecovery) {
    return <PasswordRecoveryFlow />;
  }

  if (path === routes.passwordChange) {
    return <AuthenticatedGuard><ChangePasswordFlow /></AuthenticatedGuard>;
  }


  if (path === routes.mfa) {
    return <AuthenticatedGuard><MfaSettingsFlow /></AuthenticatedGuard>;
  }


  if (path === routes.externalIdentities) {
    return <AuthenticatedGuard><ExternalIdentitySettingsFlow /></AuthenticatedGuard>;
  }

  if (path === routes.accountErasure) {
    return <AuthenticatedGuard><AccountErasureFlow /></AuthenticatedGuard>;
  }

  if (path === routes.application) {
    return <AuthenticatedGuard><ApplicationPage /></AuthenticatedGuard>;
  }

  return <ApplicationShell />;
}
