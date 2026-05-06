import { useState, useEffect, useCallback } from 'react';
import { AppLayout } from '@vaadin/react-components/AppLayout';
import { DrawerToggle } from '@vaadin/react-components/DrawerToggle';
import { Outlet, useNavigate, useLocation } from 'react-router';
import { CouncilEndpoint } from 'Frontend/generated/endpoints';
import type SavedSession from 'Frontend/generated/dev/council/model/SavedSession';
import type CouncilMember from 'Frontend/generated/dev/council/model/CouncilMember';
import './MainLayout.css';

export default function MainLayout() {
  const navigate = useNavigate();
  const location = useLocation();
  const [sessions, setSessions] = useState<SavedSession[]>([]);
  const [members, setMembers] = useState<CouncilMember[]>([]);
  const [chairman, setChairman] = useState<CouncilMember | undefined>();

  const loadSessions = useCallback(() => {
    CouncilEndpoint.listSavedSessions()
      .then(setSessions)
      .catch(err => console.warn('Failed to load sessions:', err));
  }, []);

  useEffect(() => {
    loadSessions();
    CouncilEndpoint.getCouncilMembers().then(setMembers);
    CouncilEndpoint.getChairman().then(setChairman);
  }, [loadSessions]);

  // Refresh list when a new session is saved
  useEffect(() => {
    const handler = () => loadSessions();
    window.addEventListener('session-saved', handler);
    return () => window.removeEventListener('session-saved', handler);
  }, [loadSessions]);

  const handleSessionClick = (filename: string) => {
    window.dispatchEvent(new CustomEvent('load-session', { detail: filename }));
  };

  return (
    <AppLayout primarySection="drawer">
      <div slot="drawer" className="drawer-content">
        <header className="drawer-header">
          <h1>LLM Council</h1>
          <p className="subtitle">Collaborative AI Deliberation</p>
        </header>
        <nav className="drawer-nav">
          {sessions.length > 0 && (
            <div className="session-list">
              <h3 className="session-list-header">Saved Sessions</h3>
              {sessions.map((s) => (
                <button
                  key={s.filename}
                  className="session-item"
                  onClick={() => handleSessionClick(s.filename!)}
                >
                  <span className="session-title">{s.title}</span>
                  <span className="session-date">
                    {s.session?.createdAt
                      ? new Date(s.session.createdAt).toLocaleDateString()
                      : ''}
                  </span>
                </button>
              ))}
            </div>
          )}
        </nav>
        {(chairman || members.length > 0) && (
          <div className="drawer-models">
            <h3 className="drawer-models-header">Models</h3>
            {chairman && (
              <div className="drawer-chairman">
                <h4 className="drawer-models-subheader">Chairman</h4>
                <div className="drawer-model-item">
                  <span
                    className="drawer-model-dot"
                    style={{ backgroundColor: chairman.avatarColor }}
                  />
                  <span className="drawer-model-name">{chairman.name}</span>
                </div>
              </div>
            )}
            {members.length > 0 && (
              <>
                <h4 className="drawer-models-subheader">Council Members</h4>
                {members.map((m) => (
                  <div key={m.id} className="drawer-model-item">
                    <span
                      className="drawer-model-dot"
                      style={{ backgroundColor: m.avatarColor }}
                    />
                    <span className="drawer-model-name">{m.name}</span>
                  </div>
                ))}
              </>
            )}
          </div>
        )}
      </div>

      <DrawerToggle slot="navbar" />
      <h2 slot="navbar" className="navbar-title">LLM Council</h2>
      <div slot="navbar" className="view-switcher">
        <button
          className={`view-switch-btn ${location.pathname === '/' ? 'active' : ''}`}
          onClick={() => navigate('/')}
        >
          Council
        </button>
        <button
          className={`view-switch-btn ${location.pathname === '/traces' ? 'active' : ''}`}
          onClick={() => navigate('/traces')}
        >
          Traces
        </button>
      </div>

      <main className="main-content">
        <Outlet />
      </main>
    </AppLayout>
  );
}
