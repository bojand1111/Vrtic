import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach, beforeEach } from 'vitest';

import { initI18n } from '../i18n';

beforeEach(() => {
  initI18n('sr-Latn');
});

afterEach(() => {
  cleanup();
  window.localStorage.clear();
});
