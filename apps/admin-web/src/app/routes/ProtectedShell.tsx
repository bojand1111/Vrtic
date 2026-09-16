import { AppLayout } from '../layout/AppLayout';
import { RequireAuth } from './RequireAuth';

export function ProtectedShell() {
  return (
    <RequireAuth>
      <AppLayout />
    </RequireAuth>
  );
}
