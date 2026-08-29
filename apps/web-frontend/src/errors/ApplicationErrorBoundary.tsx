import { Component, type ReactNode } from 'react';

type Props = { children: ReactNode };
type State = { failed: boolean };

export class ApplicationErrorBoundary extends Component<Props, State> {
  state: State = { failed: false };

  static getDerivedStateFromError(): State {
    return { failed: true };
  }

  override render() {
    if (!this.state.failed) return this.props.children;
    return <main>
      <section aria-labelledby="application-error-title">
        <h1 id="application-error-title">The application could not continue</h1>
        <p role="alert">No account or request details were recorded in this browser error.</p>
        <button type="button" onClick={() => window.location.reload()}>Reload application</button>
      </section>
    </main>;
  }
}
