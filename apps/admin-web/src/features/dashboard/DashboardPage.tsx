import { useTranslation } from 'react-i18next';

import { useOrg } from '../../api/org';
import { useSession } from '../../auth/useSession';
import { EmptyState } from '../../components/EmptyState';
import { Page } from '../../components/Page';
import { ManagerDashboard } from './ManagerDashboard';
import { MemberDashboard } from './MemberDashboard';

/** Home screen: managers get the organization dashboard, teachers and parents their own day. */
export function DashboardPage() {
  const { t } = useTranslation();
  const { state } = useSession();
  const { role, can, organizationId } = useOrg();
  const name = state.status === 'authenticated' ? state.user.displayName : '';

  return (
    <Page title={t('nav.dashboard')}>
      <p className="vc-muted">{t('dashboard.greeting', { name })}</p>
      {organizationId === null ? <EmptyState message={t('dashboard.noOrganization')} /> : null}
      {organizationId !== null && can('REPORT_VIEW') ? <ManagerDashboard /> : null}
      {organizationId !== null && role !== null && !can('REPORT_VIEW') ? <MemberDashboard role={role} /> : null}
    </Page>
  );
}
