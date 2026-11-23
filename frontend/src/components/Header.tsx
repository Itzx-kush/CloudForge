import { Menu, RefreshCw } from 'lucide-react';
import { StatusBadge, type BadgeTone } from './StatusBadge';
import './Header.css';

export interface HeaderProps {
  pageTitle: string;
  onMenuToggle: () => void;
  apiStatusLabel: string;
  apiStatusTone: BadgeTone;
  isRefreshing: boolean;
  onRefresh: () => void;
}

export function Header({
  pageTitle,
  onMenuToggle,
  apiStatusLabel,
  apiStatusTone,
  isRefreshing,
  onRefresh,
}: HeaderProps) {
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
        <span className="cf-header__env mono">Phase 1 Foundation</span>
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
      </div>
    </header>
  );
}
