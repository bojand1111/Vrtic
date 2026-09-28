import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import { useOrg } from '../../api/org';
import { Alert } from '../../components/Alert';
import { Page } from '../../components/Page';
import './employees.css';
import { InvitationsTab } from './InvitationsTab';
import { MembersTab } from './MembersTab';
import { StaffTab } from './StaffTab';

const TABS = ['staff', 'members', 'invitations'] as const;
type Tab = (typeof TABS)[number];

/** Managers (MEMBER_MANAGE): staff profiles, all memberships, invitations. */
export function EmployeesPage() {
  const { t } = useTranslation();
  const { can } = useOrg();
  const [tab, setTab] = useState<Tab>('staff');

  if (!can('MEMBER_MANAGE')) {
    return (
      <Page title={t('nav.employees')}>
        <Alert variant="info" title={t('ui.noAccess')} />
      </Page>
    );
  }

  return (
    <Page title={t('nav.employees')}>
      <div className="vc-tabs" role="tablist" aria-label={t('nav.employees')}>
        {TABS.map((key) => (
          <button
            key={key}
            type="button"
            role="tab"
            id={`employees-tab-${key}`}
            aria-selected={tab === key}
            aria-controls={`employees-panel-${key}`}
            className={tab === key ? 'vc-tab vc-tab--active' : 'vc-tab'}
            onClick={() => {
              setTab(key);
            }}
          >
            {t(`employees.tabs.${key}`)}
          </button>
        ))}
      </div>
      <div role="tabpanel" id={`employees-panel-${tab}`} aria-labelledby={`employees-tab-${tab}`}>
        {tab === 'staff' ? <StaffTab /> : null}
        {tab === 'members' ? <MembersTab /> : null}
        {tab === 'invitations' ? <InvitationsTab /> : null}
      </div>
    </Page>
  );
}
