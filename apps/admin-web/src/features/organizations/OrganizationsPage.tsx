import { useTranslation } from 'react-i18next';

import { TenantSwitcher } from '../../tenant/TenantSwitcher';
import { useTenant } from '../../tenant/useTenant';
import { PlaceholderPage } from '../placeholder/PlaceholderPage';

/**
 * Platform admins will manage organizations here; everyone else uses it as the tenant switcher.
 */
export function OrganizationsPage() {
  const { t } = useTranslation();
  const { isPlatformAdmin } = useTenant();

  return (
    <PlaceholderPage feature="organizations">
      {isPlatformAdmin ? <p>{t('tenant.platformAdmin')}</p> : null}
      <p>
        <TenantSwitcher id="organizations-tenant-switcher" />
      </p>
    </PlaceholderPage>
  );
}
