import { useTranslation } from 'react-i18next';

import { Badge } from '../../components/Page';
import type { AbsenceKind, AttendanceStatus } from './attendanceTypes';

interface StatusBadgesProps {
  readonly status: AttendanceStatus;
  readonly absenceKind: AbsenceKind | null;
  readonly isLate: boolean;
  readonly isUnscheduled: boolean;
  readonly isExpected: boolean;
}

const STATUS_TONE: Readonly<Record<AttendanceStatus, 'neutral' | 'success' | 'info'>> = {
  NOT_ARRIVED: 'neutral',
  CHECKED_IN: 'success',
  CHECKED_OUT: 'info',
};

/** Status, absence context, late and unscheduled flags of one child's attendance day. */
export function StatusBadges({ status, absenceKind, isLate, isUnscheduled, isExpected }: StatusBadgesProps) {
  const { t } = useTranslation();
  return (
    <span className="vc-attendance-badges">
      <Badge tone={STATUS_TONE[status]}>{t(`attendance.status.${status}`)}</Badge>
      {absenceKind === null ? null : <Badge tone="warning">{t(`attendance.absence.${absenceKind}`)}</Badge>}
      {isLate ? <Badge tone="danger">{t('attendance.late')}</Badge> : null}
      {isUnscheduled ? <Badge tone="info">{t('attendance.unscheduled')}</Badge> : null}
      {!isExpected && !isUnscheduled ? <Badge>{t('attendance.notExpected')}</Badge> : null}
    </span>
  );
}
