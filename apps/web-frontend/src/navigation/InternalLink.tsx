import { type MouseEvent, type ReactNode } from 'react';
import { navigate } from './navigate';

export function InternalLink({ children, to }: { children: ReactNode; to: string }) {
  function follow(event: MouseEvent<HTMLAnchorElement>) {
    if (
      event.button !== 0
      || event.metaKey
      || event.ctrlKey
      || event.shiftKey
      || event.altKey
    ) {
      return;
    }
    event.preventDefault();
    navigate(to);
  }

  return <a href={to} onClick={follow}>{children}</a>;
}
