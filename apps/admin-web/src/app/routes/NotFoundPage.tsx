import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';

import { PageHeader } from '../../components/PageHeader';
import { useDocumentTitle } from '../useDocumentTitle';

export function NotFoundPage() {
  const { t } = useTranslation();
  const title = t('errors.notFoundTitle');
  useDocumentTitle(title);

  return (
    <>
      <PageHeader title={title} />
      <p>{t('errors.notFoundDetail')}</p>
      <Link to="/">{t('common.backHome')}</Link>
    </>
  );
}
