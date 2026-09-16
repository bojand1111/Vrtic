import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { problemResponse, renderWithProviders } from '../../test/render';
import { LoginPage } from './LoginPage';

type FetchHandler = (url: string, init: RequestInit | undefined) => Response | Promise<Response>;

function stubFetch(handler: FetchHandler) {
  const spy = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
    return Promise.resolve(handler(url, init));
  });
  vi.stubGlobal('fetch', spy);
  return spy;
}

const healthOk = () => new Response(null, { status: 200 });

describe('LoginPage', () => {
  it('renders the 501 problem+json from POST /api/v1/auth/login in an accessible alert', async () => {
    const fetchSpy = stubFetch((url) => {
      if (url === '/api/v1/auth/login') {
        return problemResponse(501, {
          type: 'https://vrtic-connect.example/problems/not-implemented',
          title: 'Not Implemented',
          status: 501,
          detail: 'Prijava još nije implementirana.',
          requestId: 'req-42',
        });
      }
      return healthOk();
    });

    renderWithProviders(<LoginPage />, { route: '/login' });
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('E-pošta'), 'admin@vrtic.example');
    await user.type(screen.getByLabelText('Lozinka'), 'tajna-lozinka');
    await user.click(screen.getByRole('button', { name: 'Prijavi se' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Not Implemented');
    expect(alert).toHaveTextContent('Prijava još nije implementirana.');
    expect(alert).toHaveTextContent('ID zahteva: req-42');

    const loginCall = fetchSpy.mock.calls.find(([input]) => input === '/api/v1/auth/login');
    expect(loginCall).toBeDefined();
    const init = loginCall![1]!;
    expect(init.method).toBe('POST');
    expect(init.credentials).toBe('include');
    expect(new Headers(init.headers).get('content-type')).toBe('application/json');
    expect(JSON.parse(init.body as string)).toEqual({
      email: 'admin@vrtic.example',
      password: 'tajna-lozinka',
      clientKind: 'WEB',
    });

    // Nothing is ever persisted client-side.
    expect(window.localStorage.length).toBe(0);
    expect(window.sessionStorage.length).toBe(0);
    expect(document.cookie).toBe('');
  });

  it('shows a generic message when the request never reaches the server', async () => {
    stubFetch((url) => {
      if (url === '/api/v1/auth/login') {
        throw new TypeError('Failed to fetch');
      }
      return healthOk();
    });

    renderWithProviders(<LoginPage />, { route: '/login' });
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('E-pošta'), 'admin@vrtic.example');
    await user.type(screen.getByLabelText('Lozinka'), 'tajna-lozinka');
    await user.click(screen.getByRole('button', { name: 'Prijavi se' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Nije moguće povezati se sa serverom');
    expect(alert).not.toHaveTextContent('Failed to fetch');
  });

  it('validates the form client-side and does not call the login endpoint', async () => {
    const fetchSpy = stubFetch(() => healthOk());

    renderWithProviders(<LoginPage />, { route: '/login' });
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('E-pošta'), 'nije-email');
    await user.click(screen.getByRole('button', { name: 'Prijavi se' }));

    const emailInput = screen.getByLabelText('E-pošta');
    expect(emailInput).toHaveAttribute('aria-invalid', 'true');
    expect(emailInput).toHaveAccessibleDescription('Unesite ispravnu adresu e-pošte.');
    expect(screen.getByLabelText('Lozinka')).toHaveAccessibleDescription('Unesite lozinku.');
    expect(emailInput).toHaveFocus();
    expect(screen.getByRole('alert')).toHaveTextContent('Obrazac sadrži greške');

    await waitFor(() => {
      expect(fetchSpy.mock.calls.some(([input]) => input === '/api/v1/auth/login')).toBe(false);
    });
  });
});
