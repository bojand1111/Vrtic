import { act, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { changeLocale } from '../../i18n';
import { LOCALE_STORAGE_KEY } from '../../i18n/locale';
import { renderWithProviders, TEST_USER } from '../../test/render';
import { PlaceholderPage } from './PlaceholderPage';

describe('PlaceholderPage', () => {
  it('shows a localized title and empty state in all three locales', async () => {
    renderWithProviders(<PlaceholderPage feature="children" />, {
      session: { status: 'authenticated', user: TEST_USER },
    });

    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Deca');
    expect(screen.getByTestId('empty-state')).toHaveTextContent('Još nema podataka');

    await act(async () => {
      await changeLocale('sr-Cyrl');
    });
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Деца');
    expect(screen.getByTestId('empty-state')).toHaveTextContent('Нема података');
    expect(document.documentElement.lang).toBe('sr-Cyrl');

    await act(async () => {
      await changeLocale('en');
    });
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Children');
    expect(screen.getByTestId('empty-state')).toHaveTextContent('No data yet');

    // The locale preference (and only that) is persisted.
    expect(window.localStorage.getItem(LOCALE_STORAGE_KEY)).toBe('en');
    expect(window.localStorage.length).toBe(1);
  });
});
