import { InternalLink } from '../navigation/InternalLink';
import { routes } from '../routes/routes';
import { useI18n } from '../i18n/I18nProvider';

export function ApplicationPage() {
  const { t } = useI18n();
  return <main><h1>{t('application')}</h1><nav aria-label={t('accountAndTenantSettings')}>
    <InternalLink to={routes.tenantManagement}>{t('tenantManagement')}</InternalLink>{' '}
    <InternalLink to={routes.mfa}>{t('twoFactorAuthentication')}</InternalLink>{' '}
    <InternalLink to={routes.externalIdentities}>{t('externalIdentities')}</InternalLink>{' '}
    <InternalLink to={routes.accountErasure}>{t('eraseAccount')}</InternalLink>
  </nav></main>;
}
