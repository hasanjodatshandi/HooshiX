import { InternalLink } from '../navigation/InternalLink';
import { routes } from '../routes/routes';

export function ApplicationPage() {
  return <main><h1>Application</h1><nav aria-label="Account and tenant settings">
    <InternalLink to={routes.tenantManagement}>Tenant management</InternalLink>{' '}
    <InternalLink to={routes.mfa}>Two-factor authentication</InternalLink>{' '}
    <InternalLink to={routes.externalIdentities}>External identities</InternalLink>{' '}
    <InternalLink to={routes.accountErasure}>Erase account</InternalLink>
  </nav></main>;
}
