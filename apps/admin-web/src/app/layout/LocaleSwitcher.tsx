import { useTranslation } from 'react-i18next';

import { changeLocale } from '../../i18n';
import { isLocale, SUPPORTED_LOCALES } from '../../i18n/locale';

interface LocaleSwitcherProps {
  readonly id?: string;
}

export function LocaleSwitcher({ id = 'locale-switcher' }: LocaleSwitcherProps) {
  const { t, i18n } = useTranslation();
  const current = i18n.resolvedLanguage ?? i18n.language;

  return (
    <label className="vc-inline-label" htmlFor={id}>
      <span>{t('common.locale')}</span>
      <select
        id={id}
        className="vc-select"
        value={isLocale(current) ? current : 'sr-Latn'}
        onChange={(event) => {
          const next = event.target.value;
          if (isLocale(next)) {
            void changeLocale(next);
          }
        }}
      >
        {SUPPORTED_LOCALES.map((locale) => (
          <option key={locale} value={locale} lang={locale}>
            {t(`locales.${locale}`)}
          </option>
        ))}
      </select>
    </label>
  );
}
