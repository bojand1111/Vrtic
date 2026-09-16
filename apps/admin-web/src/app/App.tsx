import { QueryClientProvider } from '@tanstack/react-query';
import { createBrowserRouter, RouterProvider } from 'react-router';

import { SessionProvider } from '../auth/SessionProvider';
import { TenantProvider } from '../tenant/TenantProvider';
import { createQueryClient } from './queryClient';
import { routes } from './router';

// Created once, outside the React tree, as react-router's data API requires.
const router = createBrowserRouter(routes);
const queryClient = createQueryClient();

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <SessionProvider>
        <TenantProvider>
          <RouterProvider router={router} />
        </TenantProvider>
      </SessionProvider>
    </QueryClientProvider>
  );
}
