import type { RouteObject } from 'react-router';

import { AbsencesPage } from '../features/absences/AbsencesPage';
import { AnnouncementsPage } from '../features/announcements/AnnouncementsPage';
import { AttendancePage } from '../features/attendance/AttendancePage';
import { ForgotPasswordPage } from '../features/auth/ForgotPasswordPage';
import { InvitePage } from '../features/auth/InvitePage';
import { LoginPage } from '../features/auth/LoginPage';
import { MfaPage } from '../features/auth/MfaPage';
import { ResetPasswordPage } from '../features/auth/ResetPasswordPage';
import { VerifyEmailPage } from '../features/auth/VerifyEmailPage';
import { BillingPage } from '../features/billing/BillingPage';
import { CalendarPage } from '../features/calendar/CalendarPage';
import { ChildrenPage } from '../features/children/ChildrenPage';
import { DashboardPage } from '../features/dashboard/DashboardPage';
import { EmployeesPage } from '../features/employees/EmployeesPage';
import { GroupsPage } from '../features/groups/GroupsPage';
import { LocationsPage } from '../features/locations/LocationsPage';
import { MealsPage } from '../features/meals/MealsPage';
import { MessagesPage } from '../features/messages/MessagesPage';
import { AccountPage } from '../features/account/AccountPage';
import { OrganizationsPage } from '../features/organizations/OrganizationsPage';
import { ParentsPage } from '../features/parents/ParentsPage';
import { PhotosPage } from '../features/photos/PhotosPage';
import { ReportsPage } from '../features/reports/ReportsPage';
import { SchedulesPage } from '../features/schedules/SchedulesPage';
import { SettingsPage } from '../features/settings/SettingsPage';
import { LOGIN_PATH } from './navigation';
import { NotFoundPage } from './routes/NotFoundPage';
import { ProtectedShell } from './routes/ProtectedShell';
import { RootErrorBoundary } from './routes/RootErrorBoundary';

export const routes: RouteObject[] = [
  {
    path: LOGIN_PATH,
    Component: LoginPage,
    ErrorBoundary: RootErrorBoundary,
  },
  // Public account pages reached from e-mail links (no session required).
  { path: '/invite', Component: InvitePage, ErrorBoundary: RootErrorBoundary },
  { path: '/verify-email', Component: VerifyEmailPage, ErrorBoundary: RootErrorBoundary },
  { path: '/forgot-password', Component: ForgotPasswordPage, ErrorBoundary: RootErrorBoundary },
  { path: '/reset-password', Component: ResetPasswordPage, ErrorBoundary: RootErrorBoundary },
  // E02-B12: MFA verification / mandatory enrollment right after the password step (limited session).
  { path: '/mfa', Component: MfaPage, ErrorBoundary: RootErrorBoundary },
  {
    path: '/',
    Component: ProtectedShell,
    ErrorBoundary: RootErrorBoundary,
    children: [
      { index: true, Component: DashboardPage },
      { path: 'organizations', Component: OrganizationsPage },
      { path: 'locations', Component: LocationsPage },
      { path: 'groups', Component: GroupsPage },
      { path: 'children', Component: ChildrenPage },
      { path: 'absences', Component: AbsencesPage },
      { path: 'parents', Component: ParentsPage },
      { path: 'employees', Component: EmployeesPage },
      { path: 'attendance', Component: AttendancePage },
      { path: 'schedules', Component: SchedulesPage },
      { path: 'announcements', Component: AnnouncementsPage },
      { path: 'calendar', Component: CalendarPage },
      { path: 'meals', Component: MealsPage },
      { path: 'photos', Component: PhotosPage },
      { path: 'reports', Component: ReportsPage },
      { path: 'settings', Component: SettingsPage },
      { path: 'billing', Component: BillingPage },
      { path: 'messages', Component: MessagesPage },
      { path: 'account', Component: AccountPage },
      { path: '*', Component: NotFoundPage },
    ],
  },
];
