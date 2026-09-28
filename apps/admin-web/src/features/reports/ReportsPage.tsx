import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import { useOrg } from '../../api/org';
import { Page } from '../../components/Page';
import { tabPanelProps } from '../schedules/tabPanel';
import { Tabs } from '../schedules/Tabs';
import { AttendanceReportTab } from './AttendanceReportTab';
import { AuditLogTab } from './AuditLogTab';

type ReportTab = 'attendance' | 'audit';

/** /reports (REPORT_VIEW = OWNER/ADMIN): attendance summary with CSV export, audit log (AUDIT_READ). */
export function ReportsPage() {
  const { t } = useTranslation();
  const { can } = useOrg();
  const [tab, setTab] = useState<ReportTab>('attendance');

  if (!can('REPORT_VIEW')) {
    return (
      <Page title={t('nav.reports')}>
        <p className="vc-muted">{t('ui.noAccess')}</p>
      </Page>
    );
  }
  const tabs: readonly ReportTab[] = can('AUDIT_READ') ? ['attendance', 'audit'] : ['attendance'];

  return (
    <Page title={t('nav.reports')}>
      <Tabs id="reports" label={t('nav.reports')} items={tabs.map((key) => ({ key, label: t(`reports.tabs.${key}`) }))} active={tab} onChange={setTab} />
      <div {...tabPanelProps('reports', tab)}>{tab === 'audit' && can('AUDIT_READ') ? <AuditLogTab /> : <AttendanceReportTab />}</div>
    </Page>
  );
}
