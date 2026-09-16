import i18next, { type i18n } from 'i18next';
import { initReactI18next } from 'react-i18next';

import en from './locales/en.json';
import srCyrl from './locales/sr-Cyrl.json';
import srLatn from './locales/sr-Latn.json';
import {
  DEFAULT_LOCALE,
  isLocale,
  type Locale,
  readStoredLocale,
  setCurrentLocale,
  SUPPORTED_LOCALES,
  writeStoredLocale,
} from './locale';

export const resources = {
  'sr-Latn': { translation: srLatn },
  'sr-Cyrl': { translation: srCyrl },
  en: { translation: en },
} as const;

export type TranslationSchema = typeof srLatn;

function applyDocumentLocale(locale: Locale): void {
  setCurrentLocale(locale);
  if (typeof document !== 'undefined') {
    document.documentElement.lang = locale;
  }
}

/**
 * Initialises i18next synchronously (resources are bundled, so nothing is loaded over the network).
 * Safe to call more than once; subsequent calls only switch the language.
 */
export function initI18n(initialLocale?: Locale): i18n {
  const locale = initialLocale ?? readStoredLocale() ?? DEFAULT_LOCALE;

  if (!i18next.isInitialized) {
    void i18next.use(initReactI18next).init({
      resources,
      lng: locale,
      fallbackLng: DEFAULT_LOCALE,
      supportedLngs: [...SUPPORTED_LOCALES],
      defaultNS: 'translation',
      initAsync: false,
      interpolation: {
        // React escapes rendered strings itself; escaping twice would corrupt names (e.g. "Đorđe & sin").
        escapeValue: false,
      },
      returnNull: false,
    });
    i18next.on('languageChanged', (lng) => {
      if (isLocale(lng)) {
        applyDocumentLocale(lng);
      }
    });
  } else if (i18next.language !== locale) {
    void i18next.changeLanguage(locale);
  }

  applyDocumentLocale(locale);
  return i18next;
}

export async function changeLocale(locale: Locale): Promise<void> {
  writeStoredLocale(locale);
  await i18next.changeLanguage(locale);
}

export { i18next };
