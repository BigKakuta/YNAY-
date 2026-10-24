import { LOGIN_URL } from '../api/client';
import { Icon } from '../components/ui';

export function LoginPage() {
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
        <a className="btn btn-primary btn-lg" href={LOGIN_URL} style={{ width: '100%' }}>
          <Icon name="lock" size={16} /> Sign in with Kanidm
        </a>
        <p className="xs muted">Single sign-on is the only way in. Ask your admin for access if you don’t have an account.</p>
      </div>
    </main>
  );
}
