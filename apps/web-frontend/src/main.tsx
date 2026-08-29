import {createRoot} from 'react-dom/client';
import {App} from './routes/App';
import {ApplicationErrorBoundary} from './errors/ApplicationErrorBoundary';
import {AppStateProvider} from './state/appState';

const root = document.getElementById('root');
if (!root) throw new Error('Application root is missing');

createRoot(root).render(
  <ApplicationErrorBoundary>
    <AppStateProvider><App /></AppStateProvider>
  </ApplicationErrorBoundary>,
);
