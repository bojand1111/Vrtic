import { useTranslation } from 'react-i18next';

import { ApiProblem, NetworkError } from '../api/problem';
import { Alert } from './Alert';

/**
 * Renders any error from a query/mutation: problem+json title/detail (+ request id), network error,
 * or a generic message. Well-known problem codes get a localized explanation.
 */
export function ProblemAlert({ error }: { readonly error: unknown }) {
  const { t } = useTranslation();
  if (error === null || error === undefined) {
    return null;
  }
  if (error instanceof ApiProblem) {
    let title = error.title;
    if (error.status === 403) {
      title = t('ui.errors.forbidden');
    } else if (error.status === 404) {
      title = t('ui.errors.notFound');
    } else if (error.status === 409) {
      title = t('ui.errors.conflict');
    } else if (error.status === 422) {
      title = t('ui.errors.validation');
    }
    const details = [error.detail, ...error.errors.map((e) => `${e.field}: ${e.message}`)].filter(
      (v): v is string => v !== undefined && v.length > 0,
    );
    return (
      <Alert
        variant="error"
        title={title}
        detail={details.length === 0 ? undefined : details.join(' · ')}
        meta={error.requestId === undefined ? undefined : `${t('errors.requestId')}: ${error.requestId}`}
      />
    );
  }
  if (error instanceof NetworkError) {
    return <Alert variant="error" title={t('errors.network')} />;
  }
  return <Alert variant="error" title={t('errors.generic')} />;
}
