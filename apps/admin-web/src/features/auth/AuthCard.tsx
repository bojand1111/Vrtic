import type { ReactNode } from 'react';

import { HealthWidget } from '../../app/layout/HealthWidget';
import { LocaleSwitcher } from '../../app/layout/LocaleSwitcher';
import { useDocumentTitle } from '../../app/useDocumentTitle';

/** Public account screens (invitation, e-mail verification, password reset): same frame as the login page. */
export function AuthCard({ title, children }: { readonly title: string; readonly children: ReactNode }) {
  useDocumentTitle(title);
  return (
    <div className="vc-auth-page">
      <main className="vc-auth-card" id="main">
        <h1>{title}</h1>
        {children}
      </main>
      <footer className="vc-footer">
        <HealthWidget />
        <LocaleSwitcher />
      </footer>
    </div>
  );
}
