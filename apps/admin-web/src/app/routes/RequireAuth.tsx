import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Navigate, useLocation } from 'react-router';

import { useSession } from '../../auth/useSession';
import { LOGIN_PATH } from '../navigation';

interface RequireAuthProps {
  readonly children: ReactNode;
}

export interface LoginLocationState {
  readonly from?: string;
  readonly reason?: 'expired';
}

export function RequireAuth({ children }: RequireAuthProps) {
  const { state } = useSession();
  const { t } = useTranslation();
  const location = useLocation();

  if (state.status === 'loading') {
    return (
      <p role="status" className="vc-main">
        {t('app.loading')}
      </p>
    );
  }

  if (state.status === 'unauthenticated') {
    const loginState: LoginLocationState = {
      from: `${location.pathname}${location.search}`,
      ...(state.reason === 'expired' ? { reason: 'expired' as const } : {}),
    };
    return <Navigate to={LOGIN_PATH} replace state={loginState} />;
  }

  return children;
}
