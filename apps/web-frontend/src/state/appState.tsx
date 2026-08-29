import { createContext, useContext, useEffect, useReducer } from 'react';
import { bffClient } from '../api/bffClient';
import { getErrorMessage } from '../errors/getErrorMessage';
import { isUnauthorizedFailure } from '../errors/problem';
import { sessionRestored, sessionRestoreFailed } from './appActions';
import { loadState, saveState } from './storage';
import { appReducer, initialAppModel, type AppEvent, type AppModel } from './appReducer';

const Context = createContext<{
  state: AppModel;
  dispatch: (event: AppEvent) => void;
} | null>(null);

export function AppStateProvider({ children }: { children: React.ReactNode }) {
  const initial = loadState();
  const [state, dispatch] = useReducer(appReducer, { ...initialAppModel, ...initial });

  useEffect(() => {
    const controller = new AbortController();
    void bffClient.getSessionState({ signal: controller.signal })
      .then((session) => dispatch(sessionRestored(session.authenticated, session.tenantSelected)))
      .catch((cause: unknown) => {
        if (controller.signal.aborted) return;
        if (isUnauthorizedFailure(cause)) {
          dispatch(sessionRestored(false, false));
          return;
        }
        dispatch(sessionRestoreFailed(getErrorMessage(cause)));
      });
    return () => controller.abort();
  }, []);

  useEffect(() => {
    saveState(state);
  }, [state]);

  return <Context.Provider value={{ state, dispatch }}>{children}</Context.Provider>;
}

export function useAppState() {
  const value = useContext(Context);
  if (!value) throw new Error('AppStateProvider is missing');
  return value;
}
