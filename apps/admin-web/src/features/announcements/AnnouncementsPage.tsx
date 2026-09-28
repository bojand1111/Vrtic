import { useTranslation } from 'react-i18next';

import { useOrg } from '../../api/org';
import { Page } from '../../components/Page';
import { AnnouncementInbox } from './AnnouncementInbox';
import { ManageAnnouncements } from './ManageAnnouncements';
import './announcements.css';

export function AnnouncementsPage() {
  const { t } = useTranslation();
  const { can } = useOrg();
  return (
    <Page title={t('nav.announcements')}>
      {!can('ANNOUNCEMENT_READ') ? <p>{t('ui.noAccess')}</p> : can('ANNOUNCEMENT_MANAGE') ? <ManageAnnouncements /> : <AnnouncementInbox />}
    </Page>
  );
}
