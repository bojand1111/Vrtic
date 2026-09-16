import { useTranslation } from 'react-i18next';

import { useTenant } from './useTenant';

interface TenantSwitcherProps {
  readonly id?: string;
}

export function TenantSwitcher({ id = 'tenant-switcher' }: TenantSwitcherProps) {
  const { t } = useTranslation();
  const { organizationId, memberships, switchOrganization } = useTenant();

  if (memberships.length === 0) {
    return (
      <span className="vc-inline-label">
        <span>{t('tenant.switcherLabel')}:</span>
        <span>{t('tenant.none')}</span>
      </span>
    );
  }

  return (
    <label className="vc-inline-label" htmlFor={id}>
      <span>{t('tenant.switcherLabel')}</span>
      <select
        id={id}
        className="vc-select"
        value={organizationId ?? ''}
        onChange={(event) => {
          switchOrganization(event.target.value === '' ? null : event.target.value);
        }}
      >
        {organizationId === null ? <option value="">{t('tenant.chooseOrganization')}</option> : null}
        {memberships.map((membership) => (
          <option key={membership.organizationId} value={membership.organizationId}>
            {membership.organizationName}
          </option>
        ))}
      </select>
    </label>
  );
}
