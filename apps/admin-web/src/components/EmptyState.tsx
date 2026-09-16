import { useTranslation } from 'react-i18next';

interface EmptyStateProps {
  /** Override the default localized message. */
  readonly message?: string | undefined;
}

export function EmptyState({ message }: EmptyStateProps) {
  const { t } = useTranslation();
  return (
    <div className="vc-empty" data-testid="empty-state">
      <p>{message ?? t('common.emptyState')}</p>
    </div>
  );
}
