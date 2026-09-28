import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import { useOrg, useOrgQuery } from '../../api/org';
import { useFormat } from '../../app/format';
import { EmptyState } from '../../components/EmptyState';
import { SelectField } from '../../components/Form';
import { Modal } from '../../components/Modal';
import { Badge, Loading } from '../../components/Page';
import { ProblemAlert } from '../../components/ProblemAlert';
import { AnnouncementForm } from './AnnouncementForm';
import type { Announcement, AnnouncementRecipient, AnnouncementStatus } from './announcementTypes';
import { type NamedRef, type Page, useVersionedMutation } from './versionedMutation';

const STATUS_TONE: Readonly<Record<AnnouncementStatus, 'neutral' | 'success' | 'info'>> = {
  DRAFT: 'neutral',
  PUBLISHED: 'success',
  ARCHIVED: 'info',
};

/** ANNOUNCEMENT_MANAGE: every announcement with status filter, create/edit drafts, publish, archive, read receipts. */
export function ManageAnnouncements() {
  const { t } = useTranslation();
  const { can } = useOrg();
  const fmt = useFormat();
  const [status, setStatus] = useState('');
  const list = useOrgQuery<Page<Announcement>>(['announcements'], `/announcements?limit=100&sort=createdAt:desc${status === '' ? '' : `&status=${status}`}`);
  const groups = useOrgQuery<Page<NamedRef>>(['groups'], '/groups?limit=100');
  const locations = useOrgQuery<Page<NamedRef>>(['locations'], '/locations?limit=100');
  const action = useVersionedMutation(['announcements']);
  const [editing, setEditing] = useState<Announcement | 'new' | null>(null);
  const [recipientsOf, setRecipientsOf] = useState<Announcement | null>(null);

  const groupName = (id: string) => groups.data?.items.find((g) => g.id === id)?.name ?? t('announcements.audience.GROUP');
  const locationName = (id: string) => locations.data?.items.find((l) => l.id === id)?.name ?? t('announcements.audience.LOCATION');
  const audienceLabel = (a: Announcement) =>
    a.audiences
      .map((au) =>
        au.audienceType === 'GROUP' && au.groupId != null
          ? groupName(au.groupId)
          : au.audienceType === 'LOCATION' && au.locationId != null
            ? locationName(au.locationId)
            : t(`announcements.audience.${au.audienceType}`),
      )
      .join(', ');

  const run = (a: Announcement, kind: 'publish' | 'archive' | 'delete') => {
    const question = kind === 'publish' ? t('announcements.confirmPublish') : kind === 'archive' ? t('announcements.confirmArchive') : t('ui.confirmDelete');
    if (!window.confirm(question)) {
      return;
    }
    action.mutate(
      kind === 'delete'
        ? { method: 'DELETE', path: `/announcements/${a.id}`, version: a.version }
        : { method: 'POST', path: `/announcements/${a.id}/${kind}`, version: a.version },
    );
  };

  return (
    <>
      <div className="vc-toolbar">
        <SelectField
          id="announcement-status"
          label={t('ui.status')}
          value={status}
          onChange={setStatus}
          emptyLabel={t('ui.all')}
          options={(['DRAFT', 'PUBLISHED', 'ARCHIVED'] as const).map((s) => ({ value: s, label: t(`announcements.status.${s}`) }))}
        />
        <button type="button" className="vc-button vc-button--primary" onClick={() => { setEditing('new'); }}>
          {t('announcements.new')}
        </button>
      </div>
      <ProblemAlert error={action.error} />
      {list.isPending ? <Loading /> : null}
      {list.isError ? <ProblemAlert error={list.error} /> : null}
      {list.data?.items.length === 0 ? <EmptyState /> : null}
      {list.data === undefined || list.data.items.length === 0 ? null : (
        <div className="vc-table-wrap">
          <table className="vc-table">
            <thead>
              <tr>
                <th>{t('announcements.fields.title')}</th>
                <th>{t('ui.status')}</th>
                <th>{t('announcements.fields.audience')}</th>
                <th>{t('announcements.columns.publishedAt')}</th>
                <th>{t('announcements.columns.reads')}</th>
                <th>{t('ui.actions')}</th>
              </tr>
            </thead>
            <tbody>
              {list.data.items.map((a) => (
                <tr key={a.id}>
                  <td>{a.title}</td>
                  <td>
                    <Badge tone={STATUS_TONE[a.status]}>{t(`announcements.status.${a.status}`)}</Badge>
                  </td>
                  <td>{audienceLabel(a)}</td>
                  <td>{fmt.dateTime(a.publishedAt)}</td>
                  <td>{a.status === 'DRAFT' ? '' : `${a.readCount ?? 0} / ${a.recipientsCount ?? 0}`}</td>
                  <td className="vc-actions">
                    {a.status === 'DRAFT' ? (
                      <>
                        <button type="button" className="vc-button vc-button--small" onClick={() => { setEditing(a); }}>
                          {t('ui.edit')}
                        </button>
                        {can('ANNOUNCEMENT_PUBLISH') ? (
                          <button type="button" className="vc-button vc-button--small vc-button--primary" disabled={action.isPending} onClick={() => { run(a, 'publish'); }}>
                            {t('announcements.actions.publish')}
                          </button>
                        ) : null}
                        <button type="button" className="vc-button vc-button--small vc-button--danger" disabled={action.isPending} onClick={() => { run(a, 'delete'); }}>
                          {t('ui.delete')}
                        </button>
                      </>
                    ) : (
                      <>
                        <button type="button" className="vc-button vc-button--small" onClick={() => { setRecipientsOf(a); }}>
                          {t('announcements.actions.recipients')}
                        </button>
                        {a.status === 'PUBLISHED' ? (
                          <button type="button" className="vc-button vc-button--small" disabled={action.isPending} onClick={() => { run(a, 'archive'); }}>
                            {t('announcements.actions.archive')}
                          </button>
                        ) : null}
                      </>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {editing === null ? null : (
        <AnnouncementForm
          announcement={editing === 'new' ? null : editing}
          groups={groups.data?.items ?? []}
          locations={locations.data?.items ?? []}
          onClose={() => { setEditing(null); }}
        />
      )}
      {recipientsOf === null ? null : <RecipientsModal announcement={recipientsOf} onClose={() => { setRecipientsOf(null); }} />}
    </>
  );
}

function RecipientsModal({ announcement, onClose }: { readonly announcement: Announcement; readonly onClose: () => void }) {
  const { t } = useTranslation();
  const fmt = useFormat();
  const recipients = useOrgQuery<Page<AnnouncementRecipient>>(['announcements', 'recipients'], `/announcements/${announcement.id}/recipients?limit=100`);
  const items = recipients.data?.items ?? [];
  const read = items.filter((r) => r.readAt != null).length;
  return (
    <Modal title={`${t('announcements.recipients.title')}: ${announcement.title}`} open onClose={onClose}>
      {recipients.isPending ? <Loading /> : null}
      {recipients.isError ? <ProblemAlert error={recipients.error} /> : null}
      {recipients.data === undefined ? null : (
        <>
          <p>{t('announcements.recipients.summary', { read, total: items.length })}</p>
          <div className="vc-table-wrap">
            <table className="vc-table">
              <thead>
                <tr>
                  <th>{t('ui.name')}</th>
                  <th>{t('announcements.recipients.role')}</th>
                  <th>{t('announcements.recipients.readAt')}</th>
                </tr>
              </thead>
              <tbody>
                {items.map((r) => (
                  <tr key={r.membershipId}>
                    <td>
                      {r.givenName} {r.familyName}
                    </td>
                    <td>{t(`roles.${r.role}`)}</td>
                    <td>{r.readAt == null ? <Badge tone="warning">{t('announcements.recipients.unread')}</Badge> : fmt.dateTime(r.readAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </Modal>
  );
}
