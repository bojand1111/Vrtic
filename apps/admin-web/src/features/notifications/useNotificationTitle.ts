import { useTranslation } from 'react-i18next';

import { useFormat } from '../../app/format';
import { type AppNotification, isKnownTitleKey, titleArguments } from './notificationHelpers';

/** Localized title of a notification (i18n key + arguments; unknown keys get a generic text). */
export function useNotificationTitle(): (n: AppNotification) => string {
  const { t } = useTranslation();
  const format = useFormat();
  return (n) => {
    if (!isKnownTitleKey(n.titleKey)) {
      return t('notifications.generic');
    }
    const a = titleArguments(n.titleArgs, format.date);
    const period = { childName: a.childName ?? '', dateFrom: a.dateFrom ?? '', dateTo: a.dateTo ?? '' };
    switch (n.titleKey) {
      case 'announcement.published':
        return t('notifications.announcement.published', { title: a.title ?? '' });
      case 'absence.reported':
        return t('notifications.absence.reported', period);
      case 'absence.cancelled':
        return t('notifications.absence.cancelled', period);
      case 'guardian.confirmed':
        return t('notifications.guardian.confirmed', { childName: a.childName ?? '' });
      case 'message.received':
        return t('notifications.message.received', { senderName: a.senderName ?? '', childName: a.childName ?? '' });
    }
  };
}
