import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, type RenderResult } from '@testing-library/react';
import type { ReactNode } from 'react';
import { createMemoryRouter, RouterProvider } from 'react-router';

import { SessionProvider } from '../auth/SessionProvider';
import type { SessionState, SessionUser } from '../auth/types';
import { TenantProvider } from '../tenant/TenantProvider';

export function createTestQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, staleTime: Infinity, refetchInterval: false },
      mutations: { retry: false },
    },
  });
}

export const TEST_USER: SessionUser = {
  id: 'user-1',
  email: 'vlasnik@vrtic.example',
  displayName: 'Vlasnik Vrtića',
  isPlatformAdmin: false,
  memberships: [
    { organizationId: 'org-a', organizationName: 'Vrtić Sunce', role: 'KINDERGARTEN_OWNER' },
    { organizationId: 'org-b', organizationName: 'Vrtić Zvezdica', role: 'ADMIN' },
  ],
};

interface RenderOptions {
  readonly route?: string;
  readonly session?: SessionState;
  readonly queryClient?: QueryClient;
}

interface RenderWithProvidersResult extends RenderResult {
  readonly queryClient: QueryClient;
}

/** Renders `ui` under the same provider stack as the real app, but with a memory router. */
export function renderWithProviders(ui: ReactNode, options: RenderOptions = {}): RenderWithProvidersResult {
  const queryClient = options.queryClient ?? createTestQueryClient();
  const session = options.session ?? { status: 'unauthenticated' };
  const router = createMemoryRouter([{ path: '*', element: ui }], {
    initialEntries: [options.route ?? '/'],
  });

  const result = render(
    <QueryClientProvider client={queryClient}>
      <SessionProvider initialState={session}>
        <TenantProvider>
          <RouterProvider router={router} />
        </TenantProvider>
      </SessionProvider>
    </QueryClientProvider>,
  );

  return { ...result, queryClient };
}

export function problemResponse(
  status: number,
  body: Readonly<Record<string, unknown>>,
  contentType = 'application/problem+json',
): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': contentType },
  });
}
