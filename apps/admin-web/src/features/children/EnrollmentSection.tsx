import { type SubmitEvent, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { useOrg, useOrgMutation, useOrgQuery } from '../../api/org';
import { fieldError } from '../../api/problem';
import { todayIso, useFormat } from '../../app/format';
import { InputField, SelectField } from '../../components/Form';
import { Modal } from '../../components/Modal';
import { Badge } from '../../components/Page';
import { ProblemAlert } from '../../components/ProblemAlert';
import { addDays } from './helpers';
import type { ChildDetail, EnrollmentRef, EnrollmentStatus, GroupOption, Page } from './types';

const TONE: Readonly<Record<EnrollmentStatus, 'success' | 'info' | 'neutral' | 'warning'>> = {
  ACTIVE: 'success',
  PLANNED: 'info',
  ENDED: 'neutral',
  CANCELLED: 'warning',
};

export function EnrollmentBadge({ status }: { readonly status: EnrollmentStatus }) {
  const { t } = useTranslation();
  return <Badge tone={TONE[status]}>{t(`children.enrollmentStatus.${status}`)}</Badge>;
}

/** Current group, history, and for managers: enroll / move to another group / end an enrollment. */
export function EnrollmentSection({ child }: { readonly child: ChildDetail }) {
  const { t } = useTranslation();
  const org = useOrg();
  const format = useFormat();
  const canManage = org.can('CHILD_MANAGE');
  const [moving, setMoving] = useState(false);
  const [ending, setEnding] = useState<EnrollmentRef | null>(null);
  const current = child.currentEnrollment ?? null;

  return (
    <section className="vc-section">
      <h2>{t('children.sections.enrollment')}</h2>
      <p>
        {t('children.currentGroup')}: <strong>{current?.groupName ?? t('children.noGroup')}</strong>
        {current === null ? null : (
          <>
            {' '}
            <EnrollmentBadge status={current.status} />
          </>
        )}
      </p>
      {canManage ? (
        <div className="vc-toolbar">
          <button
            type="button"
            className="vc-button vc-button--small"
            onClick={() => {
              setMoving(true);
            }}
          >
            {current === null ? t('children.enroll') : t('children.moveToGroup')}
          </button>
        </div>
      ) : null}
      {child.enrollments.length === 0 ? (
        <p className="vc-muted">{t('children.noEnrollments')}</p>
      ) : (
        <div className="vc-table-wrap">
          <table className="vc-table">
            <thead>
              <tr>
                <th>{t('children.fields.group')}</th>
                <th>{t('ui.from')}</th>
                <th>{t('ui.to')}</th>
                <th>{t('ui.status')}</th>
                {canManage ? <th>{t('ui.actions')}</th> : null}
              </tr>
            </thead>
            <tbody>
              {child.enrollments.map((e) => (
                <tr key={e.id}>
                  <td>{e.groupName}</td>
                  <td>{format.date(e.validFrom)}</td>
                  <td>{e.validTo === undefined || e.validTo === null ? t('children.openEnded') : format.date(e.validTo)}</td>
                  <td>
                    <EnrollmentBadge status={e.status} />
                  </td>
                  {canManage ? (
                    <td className="vc-actions">
                      {e.status === 'ACTIVE' || e.status === 'PLANNED' ? (
                        <button
                          type="button"
                          className="vc-button vc-button--small vc-button--danger"
                          onClick={() => {
                            setEnding(e);
                          }}
                        >
                          {e.status === 'PLANNED' ? t('children.cancelEnrollment') : t('children.endEnrollment')}
                        </button>
                      ) : null}
                    </td>
                  ) : null}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {canManage && moving ? (
        <MoveModal
          childId={child.id}
          hasCurrent={current !== null}
          onClose={() => {
            setMoving(false);
          }}
        />
      ) : null}
      {canManage && ending !== null ? (
        <EndModal
          enrollment={ending}
          onClose={() => {
            setEnding(null);
          }}
        />
      ) : null}
    </section>
  );
}

function MoveModal({ childId, hasCurrent, onClose }: { readonly childId: string; readonly hasCurrent: boolean; readonly onClose: () => void }) {
  const { t } = useTranslation();
  const groups = useOrgQuery<Page<GroupOption>>(['groups'], '/groups?limit=100');
  const [groupId, setGroupId] = useState('');
  const [validFrom, setValidFrom] = useState(todayIso());
  const create = useOrgMutation(['children', 'schedules']);

  const submit = (e: SubmitEvent<HTMLFormElement>) => {
    e.preventDefault();
    create.mutate(
      { method: 'POST', path: `/children/${childId}/enrollments`, body: { groupId, validFrom, endCurrentEnrollment: hasCurrent } },
      { onSuccess: onClose },
    );
  };
  const options = (groups.data?.items ?? []).filter((g) => g.status === undefined || g.status === 'ACTIVE').map((g) => ({ value: g.id, label: g.name }));

  return (
    <Modal title={hasCurrent ? t('children.moveToGroup') : t('children.enroll')} open onClose={onClose}>
      <form onSubmit={submit} noValidate>
        <ProblemAlert error={groups.error} />
        <ProblemAlert error={create.error} />
        {hasCurrent ? <p className="vc-muted">{t('children.moveHint')}</p> : null}
        <SelectField id="move-group" label={t('children.fields.group')} value={groupId} onChange={setGroupId} options={options} emptyLabel={t('children.selectGroup')} required error={fieldError(create.error, 'groupId')} />
        <InputField id="move-from" type="date" label={t('children.fields.startDate')} value={validFrom} onChange={setValidFrom} required error={fieldError(create.error, 'validFrom')} />
        <footer className="vc-modal-footer">
          <button type="button" className="vc-button" onClick={onClose}>
            {t('ui.cancel')}
          </button>
          <button type="submit" className="vc-button vc-button--primary" disabled={create.isPending || groupId === ''}>
            {create.isPending ? t('ui.saving') : t('ui.save')}
          </button>
        </footer>
      </form>
    </Modal>
  );
}

function EndModal({ enrollment, onClose }: { readonly enrollment: EnrollmentRef; readonly onClose: () => void }) {
  const { t } = useTranslation();
  const planned = enrollment.status === 'PLANNED';
  const [validTo, setValidTo] = useState(planned ? addDays(enrollment.validFrom, -1) : todayIso());
  const [reason, setReason] = useState('');
  const end = useOrgMutation(['children', 'schedules']);

  const submit = (e: SubmitEvent<HTMLFormElement>) => {
    e.preventDefault();
    end.mutate(
      { method: 'POST', path: `/enrollments/${enrollment.id}/end`, body: { validTo, ...(reason.trim() === '' ? {} : { endReason: reason }) } },
      { onSuccess: onClose },
    );
  };

  return (
    <Modal title={planned ? t('children.cancelEnrollment') : t('children.endEnrollment')} open onClose={onClose}>
      <form onSubmit={submit} noValidate>
        <ProblemAlert error={end.error} />
        <p>
          {enrollment.groupName}
          {planned ? ` (${t('children.cancelPlannedHint')})` : ''}
        </p>
        {planned ? null : (
          <InputField id="end-to" type="date" label={t('children.lastDay')} value={validTo} onChange={setValidTo} required error={fieldError(end.error, 'validTo')} />
        )}
        <InputField id="end-reason" label={t('children.endReason')} value={reason} onChange={setReason} error={fieldError(end.error, 'endReason')} />
        <footer className="vc-modal-footer">
          <button type="button" className="vc-button" onClick={onClose}>
            {t('ui.cancel')}
          </button>
          <button type="submit" className="vc-button vc-button--danger" disabled={end.isPending}>
            {end.isPending ? t('ui.saving') : t('ui.confirm')}
          </button>
        </footer>
      </form>
    </Modal>
  );
}
