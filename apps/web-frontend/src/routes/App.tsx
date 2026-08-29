import { Router } from './router';
import { LanguageSelector } from '../i18n/LanguageSelector';
import { RouteFocusManager } from '../navigation/RouteFocusManager';

export function App() {
  return <>
    <header><LanguageSelector /></header>
    <RouteFocusManager />
    <Router />
  </>;
}
