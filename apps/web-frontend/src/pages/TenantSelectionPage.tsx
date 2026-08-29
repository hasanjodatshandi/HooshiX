import { useEffect, useState } from 'react';
import { bffClient, type TenantChoice } from '../api/bffClient';
import { getErrorMessage } from '../errors/getErrorMessage';
import { navigate } from '../navigation/navigate';
import { routes } from '../routes/routes';
import { useAppState } from '../state/appState';
import * as actions from '../state/appActions';
import { useI18n } from '../i18n/I18nProvider';

export function TenantSelectionPage() {
  const { t } = useI18n();
  const { dispatch } = useAppState();
  const [tenants, setTenants] = useState<TenantChoice[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    const controller = new AbortController();
    void bffClient.listTenants({ signal: controller.signal })
      .then((value) => setTenants(value.tenants))
      .catch((cause) => {
        if (!controller.signal.aborted) setError(getErrorMessage(cause));
      });
    return () => controller.abort();
  }, []);

  async function select(tenant: TenantChoice) {
    if (busy) return;
    setBusy(true);
    setError('');
    try {
      await bffClient.selectTenant(tenant.membershipId);
      dispatch(actions.tenantSelected(tenant.tenantId));
      navigate(routes.application);
    } catch (cause) {
      setError(getErrorMessage(cause));
      setBusy(false);
    }
  }

  return <main aria-labelledby="tenant-title">
    <h1 id="tenant-title">{t('tenantSelection')}</h1>
    {tenants.map((tenant) => <button type="button" disabled={busy} key={tenant.membershipId} onClick={() => void select(tenant)}>{tenant.name}</button>)}
    <p role="alert">{error}</p>
  </main>;
}
