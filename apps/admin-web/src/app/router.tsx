import type { RouteObject } from 'react-router';

import { AnnouncementsPage } from '../features/announcements/AnnouncementsPage';
import { AttendancePage } from '../features/attendance/AttendancePage';
import { LoginPage } from '../features/auth/LoginPage';
import { BillingPage } from '../features/billing/BillingPage';
import { CalendarPage } from '../features/calendar/CalendarPage';
import { ChildrenPage } from '../features/children/ChildrenPage';
import { DashboardPage } from '../features/dashboard/DashboardPage';
import { EmployeesPage } from '../features/employees/EmployeesPage';
import { GroupsPage } from '../features/groups/GroupsPage';
import { LocationsPage } from '../features/locations/LocationsPage';
import { MealsPage } from '../features/meals/MealsPage';
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
      { path: '*', Component: NotFoundPage },
    ],
  },
];
