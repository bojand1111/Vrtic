import { useTranslation } from 'react-i18next';

import { useOrg } from '../../api/org';
import { Page } from '../../components/Page';
import { ParentAttendance } from './ParentAttendance';
import { StaffAttendance } from './StaffAttendance';
import './attendance.css';

export function AttendancePage() {
  const { t } = useTranslation();
  const { role, can } = useOrg();
  return (
    <Page title={t('nav.attendance')}>
      {!can('ATTENDANCE_READ') ? <p>{t('ui.noAccess')}</p> : role === 'PARENT' ? <ParentAttendance /> : <StaffAttendance />}
    </Page>
  );
}
