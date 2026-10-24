import { useEffect, useState } from 'react';
import { Link, Navigate, Route, Routes } from 'react-router';
import { fetchMe, onUnauthorized } from './api/client';
import type { Me } from './api/types';
import { ToastProvider } from './components/toast';
import { ErrorBox, PageLoading } from './components/ui';
import { MeContext } from './context';
import { currentMonth } from './lib/dates';
import { BudgetLayout } from './pages/BudgetLayout';
import { BudgetMonthPage } from './pages/BudgetMonthPage';
import { BudgetPicker } from './pages/BudgetPicker';
import { ImportPage } from './pages/ImportPage';
import { LoginPage } from './pages/LoginPage';
import { RegisterPage } from './pages/RegisterPage';
import { ReportsPage } from './pages/ReportsPage';
import { BudgetSettingsPage, UserSettingsPage } from './pages/SettingsPage';

type Auth = { state: 'loading' } | { state: 'anonymous' } | { state: 'error'; error: Error } | { state: 'ok'; me: Me };

export function App() {
  const [auth, setAuth] = useState<Auth>({ state: 'loading' });

  useEffect(() => {
    let alive = true;
    const load = () =>
      fetchMe().then(
        (me) => alive && setAuth(me ? { state: 'ok', me } : { state: 'anonymous' }),
        (error: Error) => alive && setAuth({ state: 'error', error }),
      );
    void load();
    const off = onUnauthorized(() => setAuth({ state: 'anonymous' }));
    return () => {
      alive = false;
      off();
    };
  }, []);

  if (auth.state === 'loading') return <PageLoading />;
  if (auth.state === 'anonymous') return <LoginPage />;
  if (auth.state === 'error')
    return (
      <div className="page-narrow">
        <ErrorBox error={auth.error} onRetry={() => window.location.reload()} />
      </div>
    );

  return (
    <MeContext.Provider value={auth.me}>
      <ToastProvider>
        <Routes>
          <Route path="/" element={<BudgetPicker />} />
          <Route path="/settings" element={<UserSettingsPage />} />
          <Route path="/budgets/:budgetId" element={<BudgetLayout />}>
            <Route index element={<Navigate to={`budget/${currentMonth()}`} replace />} />
            <Route path="budget" element={<Navigate to={currentMonth()} replace />} />
            <Route path="budget/:month" element={<BudgetMonthPage />} />
            <Route path="accounts" element={<RegisterPage />} />
            <Route path="accounts/:accountId" element={<RegisterPage />} />
            <Route path="reports/:tab?" element={<ReportsPage />} />
            <Route path="import" element={<ImportPage />} />
            <Route path="settings" element={<BudgetSettingsPage />} />
            <Route path="*" element={<NotFound />} />
          </Route>
          <Route path="*" element={<NotFound />} />
        </Routes>
      </ToastProvider>
    </MeContext.Provider>
  );
}

function NotFound() {
  return (
    <div className="empty">
      <h2>Page not found</h2>
      <Link to="/">Back to your budgets</Link>
    </div>
  );
}
