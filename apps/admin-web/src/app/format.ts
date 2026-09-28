import { useTranslation } from 'react-i18next';

/** Locale-aware date/time formatting for API values (ISO date 'YYYY-MM-DD' or instant). */
export function useFormat() {
  const { i18n } = useTranslation();
  const locale = i18n.language === 'en' ? 'en-GB' : i18n.language === 'sr-Cyrl' ? 'sr-Cyrl-RS' : 'sr-Latn-RS';
  return {
    date: (iso: string | null | undefined): string => {
      if (iso === null || iso === undefined || iso.length === 0) {
        return '';
      }
      const d = iso.length === 10 ? new Date(`${iso}T00:00:00`) : new Date(iso);
      return d.toLocaleDateString(locale, { day: '2-digit', month: '2-digit', year: 'numeric' });
    },
    dateTime: (iso: string | null | undefined): string => {
      if (iso === null || iso === undefined || iso.length === 0) {
        return '';
      }
      return new Date(iso).toLocaleString(locale, { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' });
    },
    time: (iso: string | null | undefined): string => {
      if (iso === null || iso === undefined || iso.length === 0) {
        return '';
      }
      return new Date(iso).toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit' });
    },
  };
}

/** Today as 'YYYY-MM-DD' in the browser's timezone. */
export function todayIso(): string {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}
