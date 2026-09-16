import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

import type { FeatureKey } from '../../app/navigation';
import { useDocumentTitle } from '../../app/useDocumentTitle';
import { EmptyState } from '../../components/EmptyState';
import { PageHeader } from '../../components/PageHeader';

interface PlaceholderPageProps {
  readonly feature: FeatureKey;
  /** Optional extra content rendered between the header and the empty state. */
  readonly children?: ReactNode;
}

/**
 * Shared shell for every not-yet-implemented screen: localized title + empty state.
 * No fake data is ever rendered here.
 */
export function PlaceholderPage({ feature, children }: PlaceholderPageProps) {
  const { t } = useTranslation();
  const title = t(`nav.${feature}`);
  useDocumentTitle(title);

  return (
    <>
      <PageHeader title={title} />
      <p className="vc-placeholder-notice">{t('app.placeholderNotice')}</p>
      {children}
      <EmptyState />
    </>
  );
}
