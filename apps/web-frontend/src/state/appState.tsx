import { createContext, useContext, useEffect, useReducer } from 'react';
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
    dispatch({ type: 'STATE_REHYDRATED', payload: { ...initialAppModel, ...initial } });
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
