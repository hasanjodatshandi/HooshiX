import {createRoot} from 'react-dom/client';
import {App} from './routes/App';
import {AppStateProvider} from './state/appState';

createRoot(document.getElementById('root')!).render(<AppStateProvider><App /></AppStateProvider>);
