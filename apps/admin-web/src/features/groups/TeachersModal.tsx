import { type SubmitEvent, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { useOrgMutation, useOrgQuery } from '../../api/org';
import { fieldError } from '../../api/problem';
import { todayIso, useFormat } from '../../app/format';
import { FieldRow, InputField, SelectField } from '../../components/Form';
import { Modal } from '../../components/Modal';
import { Loading } from '../../components/Page';
import { ProblemAlert } from '../../components/ProblemAlert';
import { ASSIGNMENT_ROLES, type AssignmentRole, type Employee } from '../employees/types';
import type { Page } from '../locations/types';
import type { Assignment, Group } from './types';

interface TeachersModalProps {
  readonly group: Group | null;
  readonly onClose: () => void;
}

/** Manager view of one group's teacher assignments: current/future list with "end", and a form to assign. */
export function TeachersModal({ group, onClose }: TeachersModalProps) {
  const { t } = useTranslation();
  const format = useFormat();
  const open = group !== null;
  const groupId = group?.id ?? '';
  const assignments = useOrgQuery<Page<Assignment>>(['assignments'], `/group-teacher-assignments?groupId=${groupId}&limit=100`, {
    enabled: open,
  });
  const employees = useOrgQuery<Page<Employee>>(['employees'], '/employees?limit=100', { enabled: open });
  const mutation = useOrgMutation<Assignment | undefined>(['assignments', 'groups', 'employees']);
  const [employeeId, setEmployeeId] = useState('');
  const [role, setRole] = useState<AssignmentRole>('LEAD');
  const [validFrom, setValidFrom] = useState(todayIso());
  const [validTo, setValidTo] = useState('');

  const today = todayIso();
  const current = (assignments.data?.items ?? []).filter((a) => a.validTo === undefined || a.validTo === null || a.validTo >= today);
  const staff = (employees.data?.items ?? []).filter((e) => e.role !== 'PARENT');

  function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    mutation.mutate(
      {
        method: 'POST',
        path: '/group-teacher-assignments',
        body: {
          groupId,
          employeeId,
          assignmentRole: role,
          validFrom,
          ...(validTo.length > 0 ? { validTo } : {}),
        },
      },
      {
        onSuccess: () => {
          setEmployeeId('');
          setValidTo('');
        },
      },
    );
  }

  function end(assignment: Assignment) {
    if (!window.confirm(t('groups.confirmEndAssignment', { name: assignment.employeeDisplayName }))) {
      return;
    }
    mutation.mutate({ method: 'DELETE', path: `/group-teacher-assignments/${assignment.id}` });
  }

  function close() {
    mutation.reset();
    onClose();
  }

  return (
    <Modal title={t('groups.teachersOf', { name: group?.name ?? '' })} open={open} onClose={close}>
      <ProblemAlert error={mutation.error} />
      <ProblemAlert error={assignments.error} />
      <ProblemAlert error={employees.error} />
      <section className="vc-section">
        <h2>{t('groups.currentAssignments')}</h2>
        {assignments.isPending ? <Loading /> : null}
        {assignments.isSuccess && current.length === 0 ? <p className="vc-muted">{t('groups.noTeachers')}</p> : null}
        {current.length > 0 ? (
          <ul className="vc-list">
            {current.map((a) => (
              <li key={a.id}>
                <strong>{a.employeeDisplayName}</strong> ({t(`groups.assignmentRoles.${a.assignmentRole}`)}) {format.date(a.validFrom)}
                {' – '}
                {a.validTo === undefined || a.validTo === null ? t('groups.openEnded') : format.date(a.validTo)}{' '}
                <button
                  type="button"
                  className="vc-button vc-button--small vc-button--danger"
                  disabled={mutation.isPending}
                  onClick={() => {
                    end(a);
                  }}
                >
                  {t('groups.endAssignment')}
                </button>
              </li>
            ))}
          </ul>
        ) : null}
      </section>
      <form onSubmit={submit} noValidate>
        <h2>{t('groups.assignTeacher')}</h2>
        {employees.isSuccess && staff.length === 0 ? <p className="vc-muted">{t('groups.noStaff')}</p> : null}
        <SelectField
          id="assign-employee"
          label={t('groups.employee')}
          value={employeeId}
          onChange={setEmployeeId}
          emptyLabel={t('groups.chooseEmployee')}
          options={staff.map((e) => ({ value: e.id, label: `${e.displayName} (${t(`roles.${e.role}`)})` }))}
          required
          error={fieldError(mutation.error, 'employeeId')}
        />
        <SelectField
          id="assign-role"
          label={t('groups.assignmentRole')}
          value={role}
          onChange={(v) => {
            setRole(ASSIGNMENT_ROLES.find((r) => r === v) ?? 'LEAD');
          }}
          options={ASSIGNMENT_ROLES.map((r) => ({ value: r, label: t(`groups.assignmentRoles.${r}`) }))}
        />
        <FieldRow>
          <InputField
            id="assign-from"
            type="date"
            label={t('ui.from')}
            value={validFrom}
            onChange={setValidFrom}
            required
            error={fieldError(mutation.error, 'validFrom')}
          />
          <InputField
            id="assign-to"
            type="date"
            label={t('ui.to')}
            value={validTo}
            onChange={setValidTo}
            hint={t('groups.validToHint')}
            error={fieldError(mutation.error, 'validTo')}
          />
        </FieldRow>
        <div className="vc-modal-footer">
          <button type="button" className="vc-button" onClick={close}>
            {t('ui.close')}
          </button>
          <button type="submit" className="vc-button vc-button--primary" disabled={mutation.isPending || employeeId.length === 0}>
            {mutation.isPending ? t('ui.saving') : t('groups.assign')}
          </button>
        </div>
      </form>
    </Modal>
  );
}
