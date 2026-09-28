import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { useOrgMutation, useOrgQuery } from '../../api/org';
import { useFormat } from '../../app/format';
import { EmptyState } from '../../components/EmptyState';
import { Modal } from '../../components/Modal';
import { Badge, Loading } from '../../components/Page';
import { ProblemAlert } from '../../components/ProblemAlert';
import { type Announcement, inboxOrder } from './announcementTypes';
import type { Page } from './versionedMutation';

/** TEACHER / PARENT: published announcements addressed to the member, unread first. */
export function AnnouncementInbox() {
  const { t } = useTranslation();
  const fmt = useFormat();
  const list = useOrgQuery<Page<Announcement>>(['announcements'], '/announcements?limit=100');
  const [open, setOpen] = useState<Announcement | null>(null);

  if (list.isPending) {
    return <Loading />;
  }
  if (list.isError) {
    return <ProblemAlert error={list.error} />;
  }
  if (list.data.items.length === 0) {
    return <EmptyState message={t('announcements.inbox.empty')} />;
  }
  return (
    <>
      <ul className="vc-list vc-announcement-inbox">
        {inboxOrder(list.data.items).map((a) => (
          <li key={a.id}>
            <button type="button" className="vc-announcement-item" onClick={() => { setOpen(a); }}>
              <span className={a.readAt == null ? 'vc-announcement-title vc-announcement-title--unread' : 'vc-announcement-title'}>{a.title}</span>
              {a.readAt == null ? <Badge tone="info">{t('announcements.inbox.new')}</Badge> : null}
              <span className="vc-muted"> {fmt.dateTime(a.publishedAt)}</span>
            </button>
          </li>
        ))}
      </ul>
      {open === null ? null : <AnnouncementDetail announcement={open} onClose={() => { setOpen(null); }} />}
    </>
  );
}

function AnnouncementDetail({ announcement, onClose }: { readonly announcement: Announcement; readonly onClose: () => void }) {
  const fmt = useFormat();
  const markRead = useOrgMutation(['announcements']);
  const sent = useRef(false);
  const { mutate } = markRead;

  // Opening the detail is the explicit read receipt (POST /read is idempotent on the server).
  useEffect(() => {
    if (announcement.readAt == null && !sent.current) {
      sent.current = true;
      mutate({ method: 'POST', path: `/announcements/${announcement.id}/read` });
    }
  }, [announcement.id, announcement.readAt, mutate]);

  return (
    <Modal title={announcement.title} open onClose={onClose}>
      <p className="vc-muted">{fmt.dateTime(announcement.publishedAt)}</p>
      <p className="vc-pre">{announcement.body}</p>
      <ProblemAlert error={markRead.error} />
    </Modal>
  );
}
