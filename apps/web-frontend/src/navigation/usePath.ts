import { useSyncExternalStore } from 'react';

export function usePath() {
  return useSyncExternalStore(subscribe, getSnapshot);
}

function subscribe(onPathChange: () => void) {
  window.addEventListener('popstate', onPathChange);
  return () => window.removeEventListener('popstate', onPathChange);
}

function getSnapshot() {
  return window.location.pathname;
}
