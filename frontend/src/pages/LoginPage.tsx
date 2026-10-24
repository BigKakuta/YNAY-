import { LOGIN_URL } from '../api/client';
import { Icon } from '../components/ui';

/** Set by the backend after a failed login: `/?login_error` or `/?login_error=idp_unavailable`. */
function loginErrorMessage(search: string): string | null {
  const params = new URLSearchParams(search);
  if (!params.has('login_error')) return null;
  if (params.get('login_error') === 'idp_unavailable') {
    return 'Kanidm is not reachable right now. Try again in a moment.';
  }
  return 'Sign-in did not complete. Check that your account is allowed to use YNAY and try again.';
}

export function LoginPage() {
  const error = loginErrorMessage(window.location.search);
  return (
    <main className="login">
      <div className="card login-card">
        <span className="logo">
          <span className="logo-mark">Y</span> YNAY
        </span>
        <div className="stack" style={{ gap: 6 }}>
          <h1 style={{ fontSize: 'var(--fs-xl)' }}>Give every dollar a job</h1>
          <p className="muted">Your self-hosted, zero-based budget. Sign in with your homelab account to continue.</p>
        </div>
        {error && (
          <p className="login-error" role="alert">
            {error}
          </p>
        )}
        <a className="btn btn-primary btn-lg" href={LOGIN_URL} style={{ width: '100%' }}>
          <Icon name="lock" size={16} /> Sign in with Kanidm
        </a>
        <p className="xs muted">Single sign-on is the only way in. Ask your admin for access if you don’t have an account.</p>
      </div>
    </main>
  );
}
