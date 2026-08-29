import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '../i18n/I18nProvider';
import { ApplicationErrorBoundary } from './ApplicationErrorBoundary';

function Broken() {
  throw new Error('sensitive implementation detail');
  return null;
}

afterEach(() => vi.restoreAllMocks());

describe('ApplicationErrorBoundary', () => {
  it('shows a localized non-detail-leaking alert and recovery action', () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    render(<I18nProvider><ApplicationErrorBoundary><Broken /></ApplicationErrorBoundary></I18nProvider>);

    expect(screen.getByRole('alert')).not.toHaveTextContent('sensitive implementation detail');
    expect(screen.getByRole('button', { name: 'Reload application' })).toBeInTheDocument();
  });
});
