import { Link, NavLink, Route, Routes } from 'react-router';

import { DashboardPage } from './pages/DashboardPage';
import { DeliveryPage } from './pages/DeliveryPage';
import { LivePage } from './pages/LivePage';

export function App() {
  return (
    <div className="app">
      <header className="topbar">
        <Link to="/" className="brand">
          <svg viewBox="0 0 32 32" width="22" height="22" aria-hidden="true">
            <path d="M16 2 4 7v8c0 7.5 5.1 13.4 12 15 6.9-1.6 12-7.5 12-15V7z" fill="currentColor" />
          </svg>
          HookGuard
        </Link>
        <nav className="nav">
          <NavLink to="/" end className={({ isActive }) => `nav-link ${isActive ? 'nav-active' : ''}`}>
            Deliveries
          </NavLink>
          <NavLink to="/live" className={({ isActive }) => `nav-link ${isActive ? 'nav-active' : ''}`}>
            Live demo
          </NavLink>
        </nav>
      </header>
      <main className="content">
        <Routes>
          <Route path="/" element={<DashboardPage />} />
          <Route path="/live" element={<LivePage />} />
          <Route path="/deliveries/:id" element={<DeliveryPage />} />
          <Route
            path="*"
            element={
              <p className="empty">
                Page not found. <Link to="/">Back to deliveries</Link>
              </p>
            }
          />
        </Routes>
      </main>
    </div>
  );
}
