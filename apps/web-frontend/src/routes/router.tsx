import { ApplicationShell } from '../app/ApplicationShell';
import { ApplicationPage } from '../pages/ApplicationPage';
import { LoginPage } from '../pages/LoginPage';
import { TenantSelectionPage } from '../pages/TenantSelectionPage';
import { VerificationFlow } from '../features/verification/VerificationFlow';
import { ProfileFlow } from '../features/profile/ProfileFlow';
import { VerificationGuard, AuthenticatedGuard } from './guards';
import { routes } from './routes';

import { usePath } from '../navigation/usePath';

export function Router() {
  const path = usePath();

  if (path === routes.verify) {
    return <VerificationGuard><VerificationFlow /></VerificationGuard>;
  }

  if (path === routes.login) {
    return <LoginPage />;
  }

  if (path === routes.tenantSelection) {
    return <AuthenticatedGuard><TenantSelectionPage /></AuthenticatedGuard>;
  }

  if (path === routes.profile) {
    return <AuthenticatedGuard><ProfileFlow /></AuthenticatedGuard>;
  }

  if (path === routes.application) {
    return <AuthenticatedGuard><ApplicationPage /></AuthenticatedGuard>;
  }

  return <ApplicationShell />;
}
