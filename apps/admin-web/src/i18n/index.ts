import i18next, { type i18n } from 'i18next';
import { initReactI18next } from 'react-i18next';

import dashboardSrLatn from '../features/dashboard/i18n/sr-Latn.json';
import dashboardSrCyrl from '../features/dashboard/i18n/sr-Cyrl.json';
import dashboardEn from '../features/dashboard/i18n/en.json';
import locationsSrLatn from '../features/locations/i18n/sr-Latn.json';
import locationsSrCyrl from '../features/locations/i18n/sr-Cyrl.json';
import locationsEn from '../features/locations/i18n/en.json';
import groupsSrLatn from '../features/groups/i18n/sr-Latn.json';
import groupsSrCyrl from '../features/groups/i18n/sr-Cyrl.json';
import groupsEn from '../features/groups/i18n/en.json';
import employeesSrLatn from '../features/employees/i18n/sr-Latn.json';
import employeesSrCyrl from '../features/employees/i18n/sr-Cyrl.json';
import employeesEn from '../features/employees/i18n/en.json';
import childrenSrLatn from '../features/children/i18n/sr-Latn.json';
import childrenSrCyrl from '../features/children/i18n/sr-Cyrl.json';
import childrenEn from '../features/children/i18n/en.json';
import parentsSrLatn from '../features/parents/i18n/sr-Latn.json';
import parentsSrCyrl from '../features/parents/i18n/sr-Cyrl.json';
import parentsEn from '../features/parents/i18n/en.json';
import absencesSrLatn from '../features/absences/i18n/sr-Latn.json';
import absencesSrCyrl from '../features/absences/i18n/sr-Cyrl.json';
import absencesEn from '../features/absences/i18n/en.json';
import schedulesSrLatn from '../features/schedules/i18n/sr-Latn.json';
import schedulesSrCyrl from '../features/schedules/i18n/sr-Cyrl.json';
import schedulesEn from '../features/schedules/i18n/en.json';
import attendanceSrLatn from '../features/attendance/i18n/sr-Latn.json';
import attendanceSrCyrl from '../features/attendance/i18n/sr-Cyrl.json';
import attendanceEn from '../features/attendance/i18n/en.json';
import announcementsSrLatn from '../features/announcements/i18n/sr-Latn.json';
import announcementsSrCyrl from '../features/announcements/i18n/sr-Cyrl.json';
import announcementsEn from '../features/announcements/i18n/en.json';
import calendarSrLatn from '../features/calendar/i18n/sr-Latn.json';
import calendarSrCyrl from '../features/calendar/i18n/sr-Cyrl.json';
import calendarEn from '../features/calendar/i18n/en.json';
import mealsSrLatn from '../features/meals/i18n/sr-Latn.json';
import mealsSrCyrl from '../features/meals/i18n/sr-Cyrl.json';
import mealsEn from '../features/meals/i18n/en.json';
import settingsSrLatn from '../features/settings/i18n/sr-Latn.json';
import settingsSrCyrl from '../features/settings/i18n/sr-Cyrl.json';
import settingsEn from '../features/settings/i18n/en.json';
import messagesSrLatn from '../features/messages/i18n/sr-Latn.json';
import messagesSrCyrl from '../features/messages/i18n/sr-Cyrl.json';
import messagesEn from '../features/messages/i18n/en.json';
import accountSrLatn from '../features/account/i18n/sr-Latn.json';
import accountSrCyrl from '../features/account/i18n/sr-Cyrl.json';
import accountEn from '../features/account/i18n/en.json';
import notificationsSrLatn from '../features/notifications/i18n/sr-Latn.json';
import notificationsSrCyrl from '../features/notifications/i18n/sr-Cyrl.json';
import notificationsEn from '../features/notifications/i18n/en.json';
import reportsSrLatn from '../features/reports/i18n/sr-Latn.json';
import reportsSrCyrl from '../features/reports/i18n/sr-Cyrl.json';
import reportsEn from '../features/reports/i18n/en.json';
import billingSrLatn from '../features/billing/i18n/sr-Latn.json';
import billingSrCyrl from '../features/billing/i18n/sr-Cyrl.json';
import billingEn from '../features/billing/i18n/en.json';
import organizationsSrLatn from '../features/organizations/i18n/sr-Latn.json';
import organizationsSrCyrl from '../features/organizations/i18n/sr-Cyrl.json';
import organizationsEn from '../features/organizations/i18n/en.json';
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

// Each business feature owns its own namespace file (src/features/<feature>/i18n/<locale>.json).
const srLatnAll = { ...srLatn, dashboard: dashboardSrLatn, locations: locationsSrLatn, groups: groupsSrLatn, employees: employeesSrLatn, children: childrenSrLatn, parents: parentsSrLatn, absences: absencesSrLatn, schedules: schedulesSrLatn, attendance: attendanceSrLatn, announcements: announcementsSrLatn, calendar: calendarSrLatn, meals: mealsSrLatn, settings: settingsSrLatn, messages: messagesSrLatn, account: accountSrLatn, notifications: notificationsSrLatn, reports: reportsSrLatn, billing: billingSrLatn, organizations: organizationsSrLatn };
const srCyrlAll = { ...srCyrl, dashboard: dashboardSrCyrl, locations: locationsSrCyrl, groups: groupsSrCyrl, employees: employeesSrCyrl, children: childrenSrCyrl, parents: parentsSrCyrl, absences: absencesSrCyrl, schedules: schedulesSrCyrl, attendance: attendanceSrCyrl, announcements: announcementsSrCyrl, calendar: calendarSrCyrl, meals: mealsSrCyrl, settings: settingsSrCyrl, messages: messagesSrCyrl, account: accountSrCyrl, notifications: notificationsSrCyrl, reports: reportsSrCyrl, billing: billingSrCyrl, organizations: organizationsSrCyrl };
const enAll = { ...en, dashboard: dashboardEn, locations: locationsEn, groups: groupsEn, employees: employeesEn, children: childrenEn, parents: parentsEn, absences: absencesEn, schedules: schedulesEn, attendance: attendanceEn, announcements: announcementsEn, calendar: calendarEn, meals: mealsEn, settings: settingsEn, messages: messagesEn, account: accountEn, notifications: notificationsEn, reports: reportsEn, billing: billingEn, organizations: organizationsEn };

export const resources = {
  'sr-Latn': { translation: srLatnAll },
  'sr-Cyrl': { translation: srCyrlAll },
  en: { translation: enAll },
} as const;

export type TranslationSchema = typeof srLatnAll;

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
