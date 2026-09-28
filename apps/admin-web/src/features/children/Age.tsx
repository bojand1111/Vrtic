import { useTranslation } from 'react-i18next';

import { todayIso } from '../../app/format';
import { ageParts } from './helpers';

/** Age in completed years and months, e.g. "4 god. 11 mes.". */
export function Age({ dateOfBirth }: { readonly dateOfBirth: string }) {
  const { t } = useTranslation();
  const { years, months } = ageParts(dateOfBirth, todayIso());
  return <>{t('children.ageValue', { years, months })}</>;
}
