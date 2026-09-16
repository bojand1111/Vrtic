import { useTranslation } from 'react-i18next';
import { isRouteErrorResponse, Link, useRouteError } from 'react-router';

/**
 * Route-level error boundary. Internal error messages are never rendered
 * (they may contain stack traces or upstream text); only localized generic copy is shown.
 */
export function RootErrorBoundary() {
  const { t } = useTranslation();
  const error = useRouteError();
  const notFound = isRouteErrorResponse(error) && error.status === 404;

  return (
    <main className="vc-error-page" id="main">
      <h1>{notFound ? t('errors.notFoundTitle') : t('errors.unexpectedTitle')}</h1>
      <p>{notFound ? t('errors.notFoundDetail') : t('errors.unexpectedDetail')}</p>
      <Link to="/">{t('common.backHome')}</Link>
    </main>
  );
}
