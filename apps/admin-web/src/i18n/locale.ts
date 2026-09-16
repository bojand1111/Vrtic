/**
 * Locale bookkeeping that does not depend on i18next, so that low-level
 * modules (API client) can read the active locale without a circular import.
 *
 * Persisting the chosen locale in localStorage is intentional and allowed:
 * it is a UI preference, not a credential.
 */
export const SUPPORTED_LOCALES = ['sr-Latn', 'sr-Cyrl', 'en'] as const;
export type Locale = (typeof SUPPORTED_LOCALES)[number];
export const DEFAULT_LOCALE: Locale = 'sr-Latn';

export const LOCALE_STORAGE_KEY = 'vrtic-connect.admin.locale';

export function isLocale(value: unknown): value is Locale {
  return typeof value === 'string' && (SUPPORTED_LOCALES as readonly string[]).includes(value);
}

export function readStoredLocale(): Locale | null {
  try {
    const raw = globalThis.localStorage.getItem(LOCALE_STORAGE_KEY);
    return isLocale(raw) ? raw : null;
  } catch {
    return null;
  }
}

export function writeStoredLocale(locale: Locale): void {
  try {
    globalThis.localStorage.setItem(LOCALE_STORAGE_KEY, locale);
  } catch {
    // Storage may be unavailable (private mode, blocked). Preference is then session-only.
  }
}

let currentLocale: Locale = DEFAULT_LOCALE;

export function getCurrentLocale(): Locale {
  return currentLocale;
}

export function setCurrentLocale(locale: Locale): void {
  currentLocale = locale;
}
