import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { createTestQueryClient, renderWithProviders, TEST_USER } from '../test/render';
import { TenantSwitcher } from './TenantSwitcher';
import { useTenant } from './useTenant';

function TenantProbe() {
  const { organizationId, userId } = useTenant();
  return (
    <output data-testid="tenant-probe">
      {userId ?? 'no-user'}/{organizationId ?? 'no-org'}
    </output>
  );
}

describe('TenantProvider', () => {
  it('defaults to the first membership and clears the query cache when switching tenant', async () => {
    const queryClient = createTestQueryClient();
    const clearSpy = vi.spyOn(queryClient, 'clear');
    queryClient.setQueryData(['user', 'user-1', 'org', 'org-a', 'children'], ['stale']);

    renderWithProviders(
      <>
        <TenantSwitcher />
        <TenantProbe />
      </>,
      { session: { status: 'authenticated', user: TEST_USER }, queryClient },
    );

    expect(screen.getByTestId('tenant-probe')).toHaveTextContent('user-1/org-a');

    await userEvent.setup().selectOptions(screen.getByLabelText('Aktivna organizacija'), 'org-b');

    expect(clearSpy).toHaveBeenCalledTimes(1);
    expect(queryClient.getQueryData(['user', 'user-1', 'org', 'org-a', 'children'])).toBeUndefined();
    expect(screen.getByTestId('tenant-probe')).toHaveTextContent('user-1/org-b');
  });

  it('exposes no tenant when unauthenticated', () => {
    renderWithProviders(
      <>
        <TenantSwitcher />
        <TenantProbe />
      </>,
      { session: { status: 'unauthenticated' } },
    );

    expect(screen.getByTestId('tenant-probe')).toHaveTextContent('no-user/no-org');
    expect(screen.getByText('Nema dostupnih organizacija')).toBeInTheDocument();
  });
});
