import { type SubmitEvent, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { useOrgMutation, useOrgQuery } from '../../api/org';
import { ApiProblem, fieldError } from '../../api/problem';
import { CheckboxField, InputField, SelectField } from '../../components/Form';
import { Modal } from '../../components/Modal';
import { Badge } from '../../components/Page';
import { ProblemAlert } from '../../components/ProblemAlert';
import { type ChildSummary, childName, type Guardian, type GuardianRelationship, type GuardianStatus, type Page, type ParentOverview, RELATIONSHIPS } from './types';

const INVALIDATE = ['children', 'parents', 'absences', 'schedules'];

/** True when the backend refused a second mother / father for the same child. */
function relationshipTaken(error: unknown): boolean {
  return error instanceof ApiProblem && error.errors.some((e) => e.code === 'RELATIONSHIP_TAKEN');
}

export function GuardianStatusBadge({ status }: { readonly status: GuardianStatus }) {
  const { t } = useTranslation();
  const tone = status === 'CONFIRMED' ? 'success' : status === 'PENDING' ? 'warning' : 'neutral';
  return <Badge tone={tone}>{t(`children.guardianStatus.${status}`)}</Badge>;
}

interface Flags {
  isPrimary: boolean;
  canManageSchedule: boolean;
  canReportAbsence: boolean;
  canGiveConsent: boolean;
  canViewHealth: boolean;
}

const FLAG_KEYS = ['isPrimary', 'canManageSchedule', 'canReportAbsence', 'canGiveConsent', 'canViewHealth'] as const;

function FlagFields({ idPrefix, flags, onChange }: { readonly idPrefix: string; readonly flags: Flags; readonly onChange: (f: Flags) => void }) {
  const { t } = useTranslation();
  return (
    <fieldset className="vc-fieldset">
      <legend>{t('children.guardianRights')}</legend>
      {FLAG_KEYS.map((k) => (
        <CheckboxField
          key={k}
          id={`${idPrefix}-${k}`}
          label={t(`children.flags.${k}`)}
          checked={flags[k]}
          onChange={(v) => {
            onChange({ ...flags, [k]: v });
          }}
        />
      ))}
    </fieldset>
  );
}

function RelationshipField({ id, value, onChange, error }: { readonly id: string; readonly value: string; readonly onChange: (v: string) => void; readonly error?: string | undefined }) {
  const { t } = useTranslation();
  return (
    <SelectField
      id={id}
      label={t('children.relationshipLabel')}
      value={value}
      onChange={onChange}
      options={RELATIONSHIPS.map((r) => ({ value: r, label: t(`children.relationship.${r}`) }))}
      required
      error={error}
    />
  );
}

/**
 * Staff link an existing ACTIVE parent membership to a child (CONFIRMED at once). Either the child
 * (child detail) or the parent (Parents screen) is fixed; the other one is chosen here.
 */
export function GuardianLinkModal({ childId, membershipId, onClose }: { readonly childId?: string; readonly membershipId?: string; readonly onClose: () => void }) {
  const { t } = useTranslation();
  const parents = useOrgQuery<{ readonly items: readonly ParentOverview[] }>(['parents'], '/parents', { enabled: membershipId === undefined });
  const children = useOrgQuery<Page<ChildSummary>>(['children'], '/children?limit=100', { enabled: childId === undefined });
  const [selectedParent, setSelectedParent] = useState(membershipId ?? '');
  const [selectedChild, setSelectedChild] = useState(childId ?? '');
  const [relationship, setRelationship] = useState<string>('MOTHER');
  const [flags, setFlags] = useState<Flags>({ isPrimary: false, canManageSchedule: true, canReportAbsence: true, canGiveConsent: true, canViewHealth: true });
  const link = useOrgMutation(INVALIDATE);

  const parentOptions = (parents.data?.items ?? [])
    .filter((p) => p.status === 'ACTIVE' && !p.links.some((l) => l.childId === childId && l.status !== 'REVOKED'))
    .map((p) => ({ value: p.membershipId, label: `${p.displayName} (${p.email})` }));
  const childOptions = (children.data?.items ?? []).map((c) => ({ value: c.id, label: childName(c) }));

  const submit = (e: SubmitEvent<HTMLFormElement>) => {
    e.preventDefault();
    link.mutate(
      { method: 'POST', path: `/children/${selectedChild}/guardians`, body: { membershipId: selectedParent, relationship, ...flags } },
      { onSuccess: onClose },
    );
  };

  return (
    <Modal title={t('children.linkParent')} open onClose={onClose}>
      <form onSubmit={submit} noValidate>
        <ProblemAlert error={parents.error ?? children.error} />
        <ProblemAlert error={link.error} />
        {membershipId === undefined ? (
          parentOptions.length === 0 && parents.data !== undefined ? (
            <p className="vc-muted">{t('children.noParentAccounts')}</p>
          ) : (
            <SelectField id="link-parent" label={t('children.parent')} value={selectedParent} onChange={setSelectedParent} options={parentOptions} emptyLabel={t('children.selectParent')} required error={fieldError(link.error, 'membershipId')} />
          )
        ) : null}
        {childId === undefined ? (
          <SelectField id="link-child" label={t('children.child')} value={selectedChild} onChange={setSelectedChild} options={childOptions} emptyLabel={t('children.selectChild')} required />
        ) : null}
        <RelationshipField id="link-relationship" value={relationship} onChange={setRelationship} error={relationshipTaken(link.error) ? t('children.relationshipTaken') : fieldError(link.error, 'relationship')} />
        <FlagFields idPrefix="link" flags={flags} onChange={setFlags} />
        <p className="vc-muted">{t('children.linkConfirmedHint')}</p>
        <footer className="vc-modal-footer">
          <button type="button" className="vc-button" onClick={onClose}>
            {t('ui.cancel')}
          </button>
          <button type="submit" className="vc-button vc-button--primary" disabled={link.isPending || selectedParent === '' || selectedChild === ''}>
            {link.isPending ? t('ui.saving') : t('ui.save')}
          </button>
        </footer>
      </form>
    </Modal>
  );
}

export function GuardianEditModal({ guardian, onClose }: { readonly guardian: Guardian; readonly onClose: () => void }) {
  const { t } = useTranslation();
  const [relationship, setRelationship] = useState<string>(guardian.relationship);
  const [flags, setFlags] = useState<Flags>({
    isPrimary: guardian.isPrimary,
    canManageSchedule: guardian.canManageSchedule,
    canReportAbsence: guardian.canReportAbsence,
    canGiveConsent: guardian.canGiveConsent,
    canViewHealth: guardian.canViewHealth,
  });
  const update = useOrgMutation(INVALIDATE);
  const submit = (e: SubmitEvent<HTMLFormElement>) => {
    e.preventDefault();
    update.mutate({ method: 'PATCH', path: `/guardians/${guardian.id}`, body: { relationship: relationship as GuardianRelationship, ...flags } }, { onSuccess: onClose });
  };
  return (
    <Modal title={`${guardian.givenName} ${guardian.familyName}`} open onClose={onClose}>
      <form onSubmit={submit} noValidate>
        <ProblemAlert error={update.error} />
        <RelationshipField id="edit-relationship" value={relationship} onChange={setRelationship} error={relationshipTaken(update.error) ? t('children.relationshipTaken') : fieldError(update.error, 'relationship')} />
        <FlagFields idPrefix="edit" flags={flags} onChange={setFlags} />
        <footer className="vc-modal-footer">
          <button type="button" className="vc-button" onClick={onClose}>
            {t('ui.cancel')}
          </button>
          <button type="submit" className="vc-button vc-button--primary" disabled={update.isPending}>
            {update.isPending ? t('ui.saving') : t('ui.save')}
          </button>
        </footer>
      </form>
    </Modal>
  );
}

/** Revoking a link needs a reason (docs/openapi.yaml RevokeRequest, 3..500 characters). */
export function GuardianRevokeModal({ guardianId, label, onClose }: { readonly guardianId: string; readonly label: string; readonly onClose: () => void }) {
  const { t } = useTranslation();
  const [reason, setReason] = useState('');
  const revoke = useOrgMutation(INVALIDATE);
  const submit = (e: SubmitEvent<HTMLFormElement>) => {
    e.preventDefault();
    revoke.mutate({ method: 'POST', path: `/guardians/${guardianId}/revoke`, body: { reason } }, { onSuccess: onClose });
  };
  return (
    <Modal title={t('children.revokeLink')} open onClose={onClose}>
      <form onSubmit={submit} noValidate>
        <ProblemAlert error={revoke.error} />
        <p>{label}</p>
        <p className="vc-muted">{t('children.revokeHint')}</p>
        <InputField id="revoke-reason" label={t('children.revokeReason')} value={reason} onChange={setReason} required error={fieldError(revoke.error, 'reason')} />
        <footer className="vc-modal-footer">
          <button type="button" className="vc-button" onClick={onClose}>
            {t('ui.cancel')}
          </button>
          <button type="submit" className="vc-button vc-button--danger" disabled={revoke.isPending || reason.trim().length < 3}>
            {revoke.isPending ? t('ui.saving') : t('children.revoke')}
          </button>
        </footer>
      </form>
    </Modal>
  );
}
