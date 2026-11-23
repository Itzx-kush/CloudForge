import { LogOut, Menu, RefreshCw } from 'lucide-react';
import { StatusBadge, type BadgeTone } from './StatusBadge';
import type { UserProfile, WorkspaceRole } from '../types/platform';
import './Header.css';

export interface HeaderProps {
  pageTitle: string;
  onMenuToggle: () => void;
  apiStatusLabel: string;
  apiStatusTone: BadgeTone;
  isRefreshing: boolean;
  onRefresh: () => void;
  user: UserProfile | null;
  activeRole: WorkspaceRole | null;
  isLoggingOut: boolean;
  onLogout: () => void;
}

export function Header({
  pageTitle,
  onMenuToggle,
  apiStatusLabel,
  apiStatusTone,
  isRefreshing,
  onRefresh,
  user,
  activeRole,
  isLoggingOut,
  onLogout,
}: HeaderProps) {
  const initials = (user?.displayName || user?.username || 'U')
    .slice(0, 2)
    .toUpperCase();

  return (
    <header className="cf-header">
      <div className="cf-header__left">
        <button
          type="button"
          className="btn cf-header__menu-btn"
          onClick={onMenuToggle}
          aria-label="Open navigation menu"
        >
          <Menu size={18} />
        </button>
        <div className="cf-header__breadcrumb">
          <span className="cf-header__root">CloudForge</span>
          <span className="cf-header__sep" aria-hidden="true">
            /
          </span>
          <span className="cf-header__current">{pageTitle}</span>
        </div>
      </div>

      <div className="cf-header__right">
        {activeRole && <span className="cf-header__env mono">{activeRole}</span>}
        <StatusBadge
          label={`API: ${apiStatusLabel}`}
          tone={apiStatusTone}
          pulse={apiStatusTone === 'healthy'}
          size="sm"
        />
        <button
          type="button"
          className="btn cf-header__refresh"
          onClick={onRefresh}
          disabled={isRefreshing}
          aria-label="Refresh backend health status"
        >
          <RefreshCw
            size={14}
            className={isRefreshing ? 'cf-spin' : ''}
            aria-hidden="true"
          />
          <span className="cf-header__refresh-text">Refresh</span>
        </button>

        {user && (
          <div className="cf-header__user">
            {user.avatarUrl ? (
              <img
                src={user.avatarUrl}
                alt={`@${user.username} avatar`}
                className="cf-avatar cf-avatar--sm"
              />
            ) : (
              <span className="cf-avatar-fallback cf-avatar-fallback--sm" aria-hidden="true">
                {initials}
              </span>
            )}
            <span className="cf-header__username mono">@{user.username}</span>
            <button
              type="button"
              className="btn btn-sm cf-header__logout"
              onClick={onLogout}
              disabled={isLoggingOut}
              aria-label="Sign out"
            >
              <LogOut size={13} aria-hidden="true" />
              <span className="cf-header__logout-text">
                {isLoggingOut ? 'Signing out...' : 'Sign out'}
              </span>
            </button>
          </div>
        )}
      </div>
    </header>
  );
}
