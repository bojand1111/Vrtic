import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

import './styles/global.css';
import { App } from './app/App';
import { initI18n } from './i18n';

initI18n();

const container = document.getElementById('root');
if (container === null) {
  throw new Error('Missing #root element');
}

createRoot(container).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
