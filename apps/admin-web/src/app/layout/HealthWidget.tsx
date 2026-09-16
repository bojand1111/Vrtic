import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';

import { checkApiReady } from '../../api/health';
import { qk } from '../../tenant/queryKeys';

const HEALTH_REFETCH_INTERVAL_MS = 60_000;

/** Footer widget: shows only "API: OK / unavailable" - never any detail from the health endpoint. */
export function HealthWidget() {
  const { t } = useTranslation();
  const query = useQuery({
    queryKey: qk(null, null, 'health', 'ready'),
    queryFn: ({ signal }) => checkApiReady(signal),
    retry: false,
    staleTime: 0,
    refetchInterval: HEALTH_REFETCH_INTERVAL_MS,
  });

  let statusText: React.ReactNode;
  if (query.isFetching) {
    statusText = <span>{t('health.checking')}</span>;
  } else if (query.data === 'ok') {
    statusText = <span className="vc-health-ok">{t('health.ok')}</span>;
  } else {
    statusText = <span className="vc-health-down">{t('health.unavailable')}</span>;
  }

  return (
    <div className="vc-health">
      <span role="status" aria-live="polite">
        {t('health.label')}: {statusText}
      </span>
      <button
        type="button"
        className="vc-button"
        disabled={query.isFetching}
        onClick={() => {
          void query.refetch();
        }}
      >
        {t('health.recheck')}
      </button>
    </div>
  );
}
