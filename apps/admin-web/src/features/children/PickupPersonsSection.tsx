import { type SubmitEvent, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { useOrg, useOrgMutation, useOrgQuery } from '../../api/org';
import { fieldError } from '../../api/problem';
import { useFormat } from '../../app/format';
import { CheckboxField, FieldRow, InputField } from '../../components/Form';
import { Modal } from '../../components/Modal';
import { Badge, Loading } from '../../components/Page';
import { ProblemAlert } from '../../components/ProblemAlert';
import type { PickupPerson } from './types';

const INVALIDATE = ['children', 'pickup'];

/** Authorised pickup persons. Managers and linked parents (PICKUP_PERSON_MANAGE) add, edit and revoke. */
export function PickupPersonsSection({ childId }: { readonly childId: string }) {
  const { t } = useTranslation();
  const org = useOrg();
  const format = useFormat();
  const canManage = org.can('PICKUP_PERSON_MANAGE');
  const list = useOrgQuery<{ readonly items: readonly PickupPerson[] }>(['pickup', childId], `/children/${childId}/pickup-persons`);
  const [showRevoked, setShowRevoked] = useState(false);
  const [editing, setEditing] = useState<PickupPerson | 'new' | null>(null);
  const revoke = useOrgMutation(INVALIDATE);
  const items = (list.data?.items ?? []).filter((p) => showRevoked || p.status === 'ACTIVE');

  const validity = (p: PickupPerson) => {
    const from = p.validFrom === undefined || p.validFrom === null ? null : format.date(p.validFrom);
    const to = p.validTo === undefined || p.validTo === null ? null : format.date(p.validTo);
    if (from === null && to === null) {
      return t('children.pickup.always');
    }
    return `${from ?? ''} ... ${to ?? ''}`;
  };

  return (
    <section className="vc-section">
      <h2>{t('children.sections.pickup')}</h2>
      <ProblemAlert error={list.error} />
      <ProblemAlert error={revoke.error} />
      <div className="vc-toolbar">
        {canManage ? (
          <button
            type="button"
            className="vc-button vc-button--small"
            onClick={() => {
              setEditing('new');
            }}
          >
            {t('children.pickup.add')}
          </button>
        ) : null}
        <CheckboxField id={`pickup-revoked-${childId}`} label={t('children.pickup.showRevoked')} checked={showRevoked} onChange={setShowRevoked} />
      </div>
      {list.isPending ? <Loading /> : null}
      {list.data !== undefined && items.length === 0 ? <p className="vc-muted">{t('children.pickup.none')}</p> : null}
      {items.length > 0 ? (
        <div className="vc-table-wrap">
          <table className="vc-table">
            <thead>
              <tr>
                <th>{t('children.pickup.fullName')}</th>
                <th>{t('children.relationshipLabel')}</th>
                <th>{t('children.pickup.phone')}</th>
                <th>{t('children.pickup.validity')}</th>
                <th>{t('ui.note')}</th>
                <th>{t('ui.status')}</th>
                {canManage ? <th>{t('ui.actions')}</th> : null}
              </tr>
            </thead>
            <tbody>
              {items.map((p) => (
                <tr key={p.id}>
                  <td>{p.fullName}</td>
                  <td>{p.relationship ?? ''}</td>
                  <td>{p.phone ?? ''}</td>
                  <td>{validity(p)}</td>
                  <td>{p.note ?? ''}</td>
                  <td>
                    <Badge tone={p.status === 'ACTIVE' ? 'success' : 'neutral'}>{p.status === 'ACTIVE' ? t('ui.active') : t('children.pickup.revoked')}</Badge>
                  </td>
                  {canManage ? (
                    <td className="vc-actions">
                      {p.status === 'ACTIVE' ? (
                        <>
                          <button
                            type="button"
                            className="vc-button vc-button--small"
                            onClick={() => {
                              setEditing(p);
                            }}
                          >
                            {t('ui.edit')}
                          </button>
                          <button
                            type="button"
                            className="vc-button vc-button--small vc-button--danger"
                            disabled={revoke.isPending}
                            onClick={() => {
                              if (window.confirm(t('children.pickup.confirmRevoke'))) {
                                revoke.mutate({ method: 'POST', path: `/pickup-persons/${p.id}/revoke` });
                              }
                            }}
                          >
                            {t('children.revoke')}
                          </button>
                        </>
                      ) : null}
                    </td>
                  ) : null}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
      {editing === null ? null : (
        <PickupPersonModal
          childId={childId}
          person={editing === 'new' ? null : editing}
          onClose={() => {
            setEditing(null);
          }}
        />
      )}
    </section>
  );
}

function PickupPersonModal({ childId, person, onClose }: { readonly childId: string; readonly person: PickupPerson | null; readonly onClose: () => void }) {
  const { t } = useTranslation();
  const [fullName, setFullName] = useState(person?.fullName ?? '');
  const [relationship, setRelationship] = useState(person?.relationship ?? '');
  const [phone, setPhone] = useState(person?.phone ?? '');
  const [note, setNote] = useState(person?.note ?? '');
  const [validFrom, setValidFrom] = useState(person?.validFrom ?? '');
  const [validTo, setValidTo] = useState(person?.validTo ?? '');
  const save = useOrgMutation(INVALIDATE);

  const submit = (e: SubmitEvent<HTMLFormElement>) => {
    e.preventDefault();
    const orNull = (v: string) => (v.trim() === '' ? null : v.trim());
    if (person === null) {
      const body = Object.fromEntries(
        Object.entries({ fullName, relationship, phone, note, validFrom, validTo }).filter(([, v]) => v.trim() !== ''),
      );
      save.mutate({ method: 'POST', path: `/children/${childId}/pickup-persons`, body }, { onSuccess: onClose });
    } else {
      const body = { fullName, relationship: orNull(relationship), phone: orNull(phone), note: orNull(note), validFrom: orNull(validFrom), validTo: orNull(validTo) };
      save.mutate({ method: 'PATCH', path: `/pickup-persons/${person.id}`, body }, { onSuccess: onClose });
    }
  };
  const err = (field: string) => fieldError(save.error, field);

  return (
    <Modal title={person === null ? t('children.pickup.add') : t('children.pickup.edit')} open onClose={onClose}>
      <form onSubmit={submit} noValidate>
        <ProblemAlert error={save.error} />
        <InputField id="pickup-name" label={t('children.pickup.fullName')} value={fullName} onChange={setFullName} required error={err('fullName')} />
        <FieldRow>
          <InputField id="pickup-relationship" label={t('children.relationshipLabel')} value={relationship} onChange={setRelationship} error={err('relationship')} />
          <InputField id="pickup-phone" type="tel" label={t('children.pickup.phone')} value={phone} onChange={setPhone} error={err('phone')} />
        </FieldRow>
        <InputField id="pickup-note" label={t('ui.note')} value={note} onChange={setNote} hint={t('children.pickup.noteHint')} error={err('note')} />
        <FieldRow>
          <InputField id="pickup-from" type="date" label={t('ui.from')} value={validFrom} onChange={setValidFrom} error={err('validFrom')} />
          <InputField id="pickup-to" type="date" label={t('ui.to')} value={validTo} onChange={setValidTo} error={err('validTo')} />
        </FieldRow>
        <footer className="vc-modal-footer">
          <button type="button" className="vc-button" onClick={onClose}>
            {t('ui.cancel')}
          </button>
          <button type="submit" className="vc-button vc-button--primary" disabled={save.isPending}>
            {save.isPending ? t('ui.saving') : t('ui.save')}
          </button>
        </footer>
      </form>
    </Modal>
  );
}
