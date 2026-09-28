import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';

import { useOrg, useOrgQuery } from '../../api/org';
import { Loading } from '../../components/Page';
import { ProblemAlert } from '../../components/ProblemAlert';
import { WeeklyScheduleEditor } from '../schedules/WeeklyScheduleEditor';
import { BasicDataSection } from './BasicDataSection';
import { EnrollmentSection } from './EnrollmentSection';
import { GuardiansSection } from './GuardiansSection';
import { PickupPersonsSection } from './PickupPersonsSection';
import { type ChildDetail, childName } from './types';

interface Props {
  readonly childId: string;
  readonly onBack: () => void;
}

/** Child detail: basic data, enrollment, guardians, pickup persons and the weekly schedule. */
export function ChildDetailPanel({ childId, onBack }: Props) {
  const { t } = useTranslation();
  const org = useOrg();
  const detail = useOrgQuery<ChildDetail>(['children', childId], `/children/${childId}`);
  const child = detail.data;
  // A parent sees only their own guardian link; its flags decide what they may change.
  const ownLink = org.role === 'PARENT' ? child?.guardians.find((g) => g.status === 'CONFIRMED') : undefined;
  const canEditSchedule = org.can('SCHEDULE_MANAGE') && (org.role === 'OWNER' || org.role === 'ADMIN' || ownLink?.canManageSchedule === true);

  return (
    <div className="vc-child-detail">
      <div className="vc-toolbar">
        <button type="button" className="vc-button" onClick={onBack}>
          {t('ui.back')}
        </button>
        {child !== undefined && ownLink?.canReportAbsence === true ? (
          <Link className="vc-button vc-button--primary" to={`/absences?report=${encodeURIComponent(child.id)}`}>
            {t('children.reportAbsence')}
          </Link>
        ) : null}
      </div>
      <ProblemAlert error={detail.error} />
      {detail.isPending ? <Loading /> : null}
      {child === undefined ? null : (
        <>
          <h2 className="vc-child-name">{childName(child)}</h2>
          <BasicDataSection child={child} />
          <EnrollmentSection child={child} />
          <GuardiansSection child={child} />
          {org.can('PICKUP_PERSON_READ') ? <PickupPersonsSection childId={child.id} /> : null}
          {org.can('SCHEDULE_READ') ? (
            <section className="vc-section">
              <h2>{t('children.sections.schedule')}</h2>
              <WeeklyScheduleEditor childId={child.id} canEdit={canEditSchedule} />
            </section>
          ) : null}
        </>
      )}
    </div>
  );
}
