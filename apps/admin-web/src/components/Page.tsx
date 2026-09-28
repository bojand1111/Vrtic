import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

import { useDocumentTitle } from '../app/useDocumentTitle';

interface PageProps {
  readonly title: string;
  /** Buttons shown next to the title (e.g. "Add"). */
  readonly actions?: ReactNode;
  readonly children: ReactNode;
}

/** Standard business screen: document title, heading with actions, content. */
export function Page({ title, actions, children }: PageProps) {
  useDocumentTitle(title);
  return (
    <>
      <header className="vc-page-header vc-page-header--actions">
        <h1>{title}</h1>
        {actions === undefined ? null : <div className="vc-toolbar">{actions}</div>}
      </header>
      {children}
    </>
  );
}

export function Loading() {
  const { t } = useTranslation();
  return (
    <p role="status" className="vc-muted">
      {t('app.loading')}
    </p>
  );
}

export function Badge({ tone = 'neutral', children }: { readonly tone?: 'neutral' | 'success' | 'warning' | 'danger' | 'info'; readonly children: ReactNode }) {
  return <span className={`vc-badge vc-badge--${tone}`}>{children}</span>;
}
