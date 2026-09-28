import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { problemResponse, renderWithProviders } from '../../test/render';
import { InvitePage } from './InvitePage';

const TOKEN = 'vci_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA';

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

describe('InvitePage', () => {
  it('registers a new account through the invitation token', async () => {
    const fetchSpy = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
      if (url === `/api/v1/auth/invitations/${TOKEN}`) {
        return Promise.resolve(
          json({
            organization: { id: 'org-a', name: 'Vrtić Sunce' },
            role: 'TEACHER',
            email: 'nova@vrtic.example',
            expiresAt: '2026-10-05T10:00:00Z',
            requiresRegistration: true,
          }),
        );
      }
      if (url === '/api/v1/auth/register' && init?.method === 'POST') {
        return Promise.resolve(json({ status: 'AUTHENTICATED' }, 201));
      }
      return Promise.resolve(problemResponse(401, { title: 'Unauthorized', status: 401 }));
    });
    vi.stubGlobal('fetch', fetchSpy);

    renderWithProviders(<InvitePage />, { route: `/invite?token=${TOKEN}` });
    expect(await screen.findByText(/Vrtić Sunce/)).toBeInTheDocument();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('Ime'), 'Nova');
    await user.type(screen.getByLabelText('Prezime'), 'Vaspitačica');
    await user.type(screen.getByLabelText('Nova lozinka'), 'dugacka-lozinka-123');
    await user.type(screen.getByLabelText('Ponovite lozinku'), 'druga-lozinka-1234');
    await user.click(screen.getByRole('button', { name: 'Napravi nalog i prihvati' }));
    expect(await screen.findByText('Lozinke se ne poklapaju.')).toBeInTheDocument();
    expect(fetchSpy.mock.calls.some(([u]) => u === '/api/v1/auth/register')).toBe(false);

    await user.clear(screen.getByLabelText('Ponovite lozinku'));
    await user.type(screen.getByLabelText('Ponovite lozinku'), 'dugacka-lozinka-123');
    await user.click(screen.getByRole('button', { name: 'Napravi nalog i prihvati' }));

    await waitFor(() => {
      expect(fetchSpy.mock.calls.some(([u]) => u === '/api/v1/auth/register')).toBe(true);
    });
    const call = fetchSpy.mock.calls.find(([u]) => u === '/api/v1/auth/register');
    expect(JSON.parse(call?.[1]?.body as string)).toEqual({
      invitationToken: TOKEN,
      password: 'dugacka-lozinka-123',
      givenName: 'Nova',
      familyName: 'Vaspitačica',
      preferredLocale: 'sr-Latn',
      device: { clientKind: 'WEB' },
    });
  });

  it('explains an unknown or expired link', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(problemResponse(404, { title: 'Not found', status: 404, detail: 'TOKEN_INVALID' }))),
    );
    renderWithProviders(<InvitePage />, { route: `/invite?token=${TOKEN}` });
    expect(await screen.findByRole('alert')).toHaveTextContent('Link pozivnice nije ispravan');
  });
});
