import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { Modal } from '../../components/Modal';
import { Loading } from '../../components/Page';
import { ProblemAlert } from '../../components/ProblemAlert';
import { useTenant } from '../../tenant/useTenant';
import { NotificationItem } from './NotificationItem';
import { useMarkNotificationsRead, useNotifications } from './useNotifications';
import './notifications.css';

const LATEST = 8;

/**
 * Header bell: unread badge (polled every 60 s), a dropdown with the latest notifications and a
 * "all notifications" panel. Covers every organization of the user; the organization name is shown
 * when the user belongs to more than one.
 */
export function NotificationBell() {
  const { t } = useTranslation();
  const { memberships } = useTenant();
  const [open, setOpen] = useState(false);
  const [showAll, setShowAll] = useState(false);
  const rootRef = useRef<HTMLDivElement | null>(null);
  const latest = useNotifications({ limit: LATEST, poll: true });
  const markAll = useMarkNotificationsRead();
  const unread = latest.data?.unreadCount ?? 0;
  const multiOrg = new Set(memberships.map((m) => m.organizationId)).size > 1;

  useEffect(() => {
    if (!open) {
      return undefined;
    }
    const onPointer = (e: MouseEvent) => {
      if (rootRef.current !== null && e.target instanceof Node && !rootRef.current.contains(e.target)) {
        setOpen(false);
      }
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', onPointer);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onPointer);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  return (
    <div className="vc-bell" ref={rootRef}>
      <button
        type="button"
        className="vc-button vc-bell-button"
        aria-haspopup="true"
        aria-expanded={open}
        aria-label={unread > 0 ? t('notifications.bellUnread', { count: unread }) : t('notifications.bell')}
        onClick={() => {
          setOpen((v) => !v);
        }}
      >
        <svg aria-hidden="true" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9" />
          <path d="M10.3 21a1.94 1.94 0 0 0 3.4 0" />
        </svg>
        {unread > 0 ? <span className="vc-bell-badge">{unread > 99 ? '99+' : unread}</span> : null}
      </button>
      {open ? (
        <div className="vc-bell-panel" role="region" aria-label={t('notifications.latest')}>
          <div className="vc-bell-panel-header">
            <strong>{t('notifications.latest')}</strong>
            {unread > 0 ? (
              <button
                type="button"
                className="vc-button vc-button--small vc-button--ghost"
                disabled={markAll.isPending}
                onClick={() => {
                  markAll.mutate(null);
                }}
              >
                {t('notifications.markAllRead')}
              </button>
            ) : null}
          </div>
          <ProblemAlert error={latest.error ?? markAll.error} />
          {latest.isPending ? <Loading /> : null}
          {latest.data?.items.length === 0 ? <p className="vc-muted">{t('notifications.empty')}</p> : null}
          <ul className="vc-notification-list">
            {(latest.data?.items ?? []).map((n) => (
              <NotificationItem
                key={n.id}
                notification={n}
                showOrganization={multiOrg}
                onOpened={() => {
                  setOpen(false);
                }}
              />
            ))}
          </ul>
          <button
            type="button"
            className="vc-button vc-button--small"
            onClick={() => {
              setOpen(false);
              setShowAll(true);
            }}
          >
            {t('notifications.all')}
          </button>
        </div>
      ) : null}
      {showAll ? (
        <AllNotifications
          multiOrg={multiOrg}
          onClose={() => {
            setShowAll(false);
          }}
        />
      ) : null}
    </div>
  );
}

/** "Sve notifikacije": the newest 100 with an unread filter and "mark all as read". */
function AllNotifications({ multiOrg, onClose }: { readonly multiOrg: boolean; readonly onClose: () => void }) {
  const { t } = useTranslation();
  const [unreadOnly, setUnreadOnly] = useState(false);
  const list = useNotifications({ limit: 100, unreadOnly });
  const markAll = useMarkNotificationsRead();
  return (
    <Modal
      title={t('notifications.all')}
      open
      onClose={onClose}
      footer={
        <>
          <button
            type="button"
            className="vc-button"
            disabled={markAll.isPending || (list.data?.unreadCount ?? 0) === 0}
            onClick={() => {
              markAll.mutate(null);
            }}
          >
            {t('notifications.markAllRead')}
          </button>
          <button type="button" className="vc-button vc-button--primary" onClick={onClose}>
            {t('ui.close')}
          </button>
        </>
      }
    >
      <label className="vc-inline-label">
        <input
          type="checkbox"
          checked={unreadOnly}
          onChange={(e) => {
            setUnreadOnly(e.target.checked);
          }}
        />
        <span>{t('notifications.unreadOnly')}</span>
      </label>
      <ProblemAlert error={list.error ?? markAll.error} />
      {list.isPending ? <Loading /> : null}
      {list.data?.items.length === 0 ? <p className="vc-muted">{t('notifications.empty')}</p> : null}
      <ul className="vc-notification-list vc-notification-list--all">
        {(list.data?.items ?? []).map((n) => (
          <NotificationItem key={n.id} notification={n} showOrganization={multiOrg} onOpened={onClose} />
        ))}
      </ul>
    </Modal>
  );
}
