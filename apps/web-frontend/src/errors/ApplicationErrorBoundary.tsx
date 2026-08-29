import { Component, type ReactNode } from 'react';
import { useI18n } from '../i18n/I18nProvider';
import type { MessageKey } from '../i18n/resources';

type Props = { children: ReactNode; t: (key: MessageKey) => string };
type State = { failed: boolean };

class ErrorBoundary extends Component<Props, State> {
  state: State = { failed: false };

  static getDerivedStateFromError(): State {
    return { failed: true };
  }

  override render() {
    if (!this.state.failed) return this.props.children;
    return <main>
      <section aria-labelledby="application-error-title">
        <h1 id="application-error-title">{this.props.t('applicationFailed')}</h1>
        <p role="alert">{this.props.t('applicationFailedPrivacy')}</p>
        <button type="button" onClick={() => window.location.reload()}>{this.props.t('reloadApplication')}</button>
      </section>
    </main>;
  }
}

export function ApplicationErrorBoundary({ children }: { children: ReactNode }) {
  const { t } = useI18n();
  return <ErrorBoundary t={t}>{children}</ErrorBoundary>;
}
