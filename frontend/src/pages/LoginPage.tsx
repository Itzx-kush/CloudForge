import { ForgeStack } from '../components/ForgeStack';
import { useEffect, useMemo, useState } from 'react';
import { Navigate, useSearchParams } from 'react-router-dom';
import {
  AlertTriangle,
  Cloud,
  Github,
  Lock,
  ShieldCheck,
  Workflow,
} from 'lucide-react';
import { StatusBadge } from '../components/StatusBadge';
import { useAuth } from '../context/AuthContext';
import type { BackendHealthState } from '../hooks/useBackendHealth';
import './LoginPage.css';

const ERROR_MESSAGES: Record<string, string> = {
  oauth_failed:
    'GitHub authentication could not be completed. Please try signing in again.',
  access_denied:
    'GitHub sign-in was cancelled before authorization completed.',
  invalid_state:
    'Your sign-in session state expired or did not match. Please restart GitHub sign-in.',
  oauth_not_configured:
    'GitHub OAuth credentials are not configured on the backend yet. Set GITHUB_OAUTH_CLIENT_ID and GITHUB_OAUTH_CLIENT_SECRET in your local .env file.',
};

export interface LoginPageProps {
  healthState: BackendHealthState;
}

export function LoginPage({ healthState }: LoginPageProps) {
  const { status, authConfig } = useAuth();
  const [searchParams] = useSearchParams();

  const [intro] = useState(() => {
    try { return !sessionStorage.getItem('cloudforge.introduced'); } catch { return false; }
  });
  useEffect(() => {
    if (intro) {
      try { sessionStorage.setItem('cloudforge.introduced', '1'); } catch { /* Optional animation. */ }
    }
  }, [intro]);

  const errorCode = searchParams.get('error');
  const errorText = useMemo(() => {
    if (!errorCode) {
      return null;
    }
    return ERROR_MESSAGES[errorCode] ?? ERROR_MESSAGES.oauth_failed;
  }, [errorCode]);

  if (status === 'authenticated') {
    return <Navigate to="/" replace />;
  }

  const oauthConfigured = authConfig?.githubOAuthConfigured ?? false;
  const loginUrl = authConfig?.loginUrl ?? '/oauth2/authorization/github';
  const payload = healthState.snapshot?.payload ?? null;
  const apiHealthy = payload?.status === 'UP' && payload?.database.status === 'UP';

  return (
    <div className="cf-login">
      <div className="cf-login__frame">
      <div className="cf-login__visual" data-intro={intro}>
        <span className="cf-probe__label"><Cloud size={17} /> CloudForge</span>
        <ForgeStack />
        <div><h2>Your cloud<br />control plane.</h2><p>Projects, environments and GitHub connections in one workspace.</p></div>
      </div>
      <div className="cf-login__card">
        <div className="cf-login__brand">
          <div className="cf-login__logo" aria-hidden="true">
            <Cloud size={22} />
          </div>
          <div>
            <h1 className="cf-login__title">CloudForge</h1>
            <p className="cf-login__tagline">
              Self-Service Cloud Deployment &amp; DevOps Control Plane
            </p>
          </div>
        </div>

        <div className="cf-login__divider" />

        <div className="cf-login__intro">
          <h2>Sign in to your developer workspace</h2>
          <p>
            Use your GitHub account to access your team’s projects and environments.
          </p>
        </div>

        {errorText && (
          <div className="cf-notice cf-notice--error" role="alert">
            <div className="cf-notice__icon" aria-hidden="true">
              <AlertTriangle size={18} />
            </div>
            <div>
              <p className="cf-notice__title">Authentication notice</p>
              <p className="cf-notice__text">{errorText}</p>
            </div>
          </div>
        )}

        {authConfig && !oauthConfigured && (
          <div className="cf-notice cf-notice--warning" role="status">
            <div className="cf-notice__icon" aria-hidden="true">
              <AlertTriangle size={18} />
            </div>
            <div>
              <p className="cf-notice__title">GitHub OAuth Setup Required</p>
              <p className="cf-notice__text">
                Configure <span className="mono">GITHUB_OAUTH_CLIENT_ID</span> and{' '}
                <span className="mono">GITHUB_OAUTH_CLIENT_SECRET</span> in your backend
                environment, register callback URL{' '}
                <span className="mono">
                  {typeof window !== 'undefined' && window.location.origin
                    ? `${window.location.origin}/login/oauth2/code/github`
                    : '/login/oauth2/code/github'}
                </span>
                , and restart the backend. See{' '}
                <span className="mono">docs/GITHUB_INTEGRATION.md</span>.
              </p>
            </div>
          </div>
        )}

        <div className="cf-login__actions">
          {oauthConfigured ? (
            <a href={loginUrl} className="btn btn-primary cf-login__cta">
              <Github size={17} aria-hidden="true" />
              <span>Continue with GitHub</span>
            </a>
          ) : (
            <button
              type="button"
              className="btn btn-primary cf-login__cta"
              disabled
              title="Configure GitHub OAuth environment variables on the backend to enable sign-in."
            >
              <Github size={17} aria-hidden="true" />
              <span>Continue with GitHub (Setup Required)</span>
            </button>
          )}
        </div>

        <ul className="cf-login__highlights">
          <li>
            <ShieldCheck size={15} aria-hidden="true" />
            <span>HTTP-only server-side sessions with CSRF protection</span>
          </li>
          <li>
            <Lock size={15} aria-hidden="true" />
            <span>Least-privilege OAuth scopes; GitHub tokens never reach browser JS</span>
          </li>
          <li>
            <Workflow size={15} aria-hidden="true" />
            <span>Workspace RBAC (Owner, Developer, Viewer) backed by PostgreSQL 17</span>
          </li>
        </ul>

        <div className="cf-login__footer">
          <span className="mono cf-muted">CloudForge v1.0 · DevOps Control Plane</span>
          <StatusBadge
            label={
              payload
                ? apiHealthy
                  ? 'API & DB Healthy'
                  : 'API Degraded'
                : healthState.isLoading
                  ? 'Checking API'
                  : 'API Offline'
            }
            tone={
              payload
                ? apiHealthy
                  ? 'healthy'
                  : 'degraded'
                : healthState.isLoading
                  ? 'info'
                  : 'error'
            }
            pulse={Boolean(apiHealthy)}
            size="sm"
          />
        </div>
      </div>
      </div>
    </div>
  );
}
