import {createRoot} from 'react-dom/client';
import {App} from './routes/App';
import {ApplicationErrorBoundary} from './errors/ApplicationErrorBoundary';
import {AppStateProvider} from './state/appState';
import {I18nProvider} from './i18n/I18nProvider';

const root = document.getElementById('root');
if (!root) throw new Error('Application root is missing');

createRoot(root).render(
  <I18nProvider>
    <ApplicationErrorBoundary>
      <AppStateProvider><App /></AppStateProvider>
    </ApplicationErrorBoundary>
  </I18nProvider>,
);
