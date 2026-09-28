import { useTranslation } from 'react-i18next';

import { isKnownFlag, type SubscriptionStatus } from './billingTypes';

export function statusTone(status: SubscriptionStatus): 'success' | 'info' | 'warning' | 'danger' {
  if (status === 'ACTIVE') {
    return 'success';
  }
  if (status === 'TRIAL') {
    return 'info';
  }
  return status === 'PAST_DUE' ? 'warning' : 'danger';
}

/** Localized flag name for the known flags, the raw key otherwise. */
export function useFlagName(): (key: string) => string {
  const { t } = useTranslation();
  return (key) => (isKnownFlag(key) ? t(`billing.flagNames.${key}`) : key);
}
