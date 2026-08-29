import { useEffect, useRef } from 'react';
import { usePath } from './usePath';

export function RouteFocusManager() {
  const path = usePath();
  const firstRender = useRef(true);

  useEffect(() => {
    if (firstRender.current) {
      firstRender.current = false;
      return;
    }
    const heading = document.querySelector<HTMLElement>('main h1, main h2');
    if (!heading) return;
    heading.tabIndex = -1;
    heading.focus();
  }, [path]);

  return null;
}
