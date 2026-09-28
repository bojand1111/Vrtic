import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { NavLink, Outlet } from 'react-router';

import { logout } from '../../api/auth';
import { useOrg } from '../../api/org';
import { useSession } from '../../auth/useSession';
import { TenantSwitcher } from '../../tenant/TenantSwitcher';
import { useTenant } from '../../tenant/useTenant';
import { FEATURES, isFeatureVisible } from '../navigation';
import { HealthWidget } from './HealthWidget';
import { LocaleSwitcher } from './LocaleSwitcher';

export function AppLayout() {
  const { t } = useTranslation();
  const { state, markUnauthenticated } = useSession();
  const { organizationId, isPlatformAdmin } = useTenant();
  const { can, role } = useOrg();
  const queryClient = useQueryClient();

  const logoutMutation = useMutation({
    mutationFn: logout,
    onSettled: () => {
      // Regardless of the backend answer, forget the local session view and every cached query.
      queryClient.clear();
      markUnauthenticated();
    },
  });

  const email = state.status === 'authenticated' ? state.user.email : '';

  return (
    <div className="vc-shell">
      <a className="vc-skip-link" href="#main">
        {t('app.skipToContent')}
      </a>

      <header className="vc-header">
        <p className="vc-brand">
          {t('app.name')} · {t('app.admin')}
        </p>
        <div className="vc-header-tools">
          <TenantSwitcher />
          <LocaleSwitcher />
          {email.length > 0 ? (
            <span>
              {email}
              {role === null ? null : ` · ${t(`roles.${role}`)}`}
            </span>
          ) : null}
          <button
            type="button"
            className="vc-button"
            disabled={logoutMutation.isPending}
            onClick={() => {
              logoutMutation.mutate();
            }}
          >
            {t('common.logout')}
          </button>
        </div>
      </header>

      <nav className="vc-nav" aria-label={t('nav.label')}>
        <ul>
          {FEATURES.filter((feature) => isFeatureVisible(feature, can, isPlatformAdmin)).map((feature) => (
            <li key={feature.key}>
              <NavLink to={feature.path} end={feature.path === '/'}>
                {t(`nav.${feature.key}`)}
              </NavLink>
            </li>
          ))}
        </ul>
      </nav>

      {/* Keying the outlet by tenant remounts the page when the organization changes. */}
      <main id="main" className="vc-main" tabIndex={-1}>
        <Outlet key={organizationId ?? 'none'} />
      </main>

      <footer className="vc-footer">
        <HealthWidget />
      </footer>
    </div>
  );
}
