import { act, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { RouteFocusManager } from './RouteFocusManager';
import { usePath } from './usePath';

function Example() {
  const path = usePath();
  return <><RouteFocusManager /><main><h1>{path === '/second' ? 'Second' : 'First'}</h1></main></>;
}

describe('RouteFocusManager', () => {
  it('moves focus to the new page heading after client-side navigation', () => {
    window.history.replaceState({}, '', '/first');
    render(<Example />);

    act(() => {
      window.history.pushState({}, '', '/second');
      window.dispatchEvent(new PopStateEvent('popstate'));
    });

    expect(screen.getByRole('heading', { name: 'Second' })).toHaveFocus();
  });
});
