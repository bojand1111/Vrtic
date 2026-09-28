import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';

import { useFormat } from '../../app/format';
import { useTenant } from '../../tenant/useTenant';
import { type AppNotification, targetPath } from './notificationHelpers';
import { useMarkNotificationsRead } from './useNotifications';
import { useNotificationTitle } from './useNotificationTitle';

interface Props {
  readonly notification: AppNotification;
  readonly showOrganization: boolean;
  /** Called after the click was handled (e.g. to close the dropdown). */
  readonly onOpened?: () => void;
}

/**
 * One inbox row. Clicking marks it read, switches to its organization when needed and opens the related screen.
 * Text is rendered as plain React text (never HTML).
 */
export function NotificationItem({ notification, showOrganization, onOpened }: Props) {
  const { t } = useTranslation();
  const format = useFormat();
  const title = useNotificationTitle();
  const navigate = useNavigate();
  const { organizationId, switchOrganization } = useTenant();
  const markRead = useMarkNotificationsRead();
  const unread = (notification.readAt ?? null) === null;

  const open = () => {
    if (unread) {
      markRead.mutate([notification.id]);
    }
    const target = targetPath(notification);
    const org = notification.organizationId ?? null;
    if (target !== null && org !== null && org !== organizationId) {
      switchOrganization(org);
    }
    if (target !== null) {
      void navigate(target);
    }
    onOpened?.();
  };

  return (
    <li className={unread ? 'vc-notification vc-notification--unread' : 'vc-notification'}>
      <button type="button" className="vc-notification-button" onClick={open}>
        <span className="vc-notification-title">{title(notification)}</span>
        <span className="vc-notification-meta">
          {format.dateTime(notification.createdAt)}
          {showOrganization && (notification.organizationName ?? '') !== '' ? ` · ${notification.organizationName ?? ''}` : ''}
          {unread ? ` · ${t('notifications.unread')}` : ''}
        </span>
      </button>
    </li>
  );
}
