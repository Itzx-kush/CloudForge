import { NavLink } from 'react-router-dom';
import {
  Activity,
  Cloud,
  FolderKanban,
  GitBranch,
  LayoutDashboard,
  Rocket,
  Settings,
  Workflow,
  X,
} from 'lucide-react';
import { StatusBadge, type BadgeTone } from './StatusBadge';
import './Sidebar.css';

export interface SidebarProps {
  isOpen: boolean;
  onClose: () => void;
  apiStatusLabel: string;
  apiStatusTone: BadgeTone;
}

const NAV_ITEMS = [
  { to: '/', label: 'Dashboard', icon: LayoutDashboard, end: true },
  { to: '/projects', label: 'Projects', icon: FolderKanban },
  { to: '/repositories', label: 'Repositories', icon: GitBranch },
  { to: '/deployments', label: 'Deployments', icon: Rocket },
  { to: '/pipelines', label: 'Pipelines', icon: Workflow },
  { to: '/monitoring', label: 'Monitoring', icon: Activity },
  { to: '/settings', label: 'Settings', icon: Settings },
] as const;

export function Sidebar({
  isOpen,
  onClose,
  apiStatusLabel,
  apiStatusTone,
}: SidebarProps) {
  return (
    <>
      {isOpen && (
        <div
          className="cf-sidebar-backdrop"
          onClick={onClose}
          aria-hidden="true"
        />
      )}
      <aside
        className={`cf-sidebar ${isOpen ? 'cf-sidebar--open' : ''}`}
        aria-label="Primary navigation"
      >
        <div className="cf-sidebar__brand">
          <div className="cf-sidebar__brand-left">
            <div className="cf-sidebar__logo" aria-hidden="true">
              <Cloud size={18} />
            </div>
            <div>
              <div className="cf-sidebar__name">CloudForge</div>
              <div className="cf-sidebar__tagline">DevOps Control Plane</div>
            </div>
          </div>
          <button
            type="button"
            className="btn-ghost cf-sidebar__close"
            onClick={onClose}
            aria-label="Close navigation menu"
          >
            <X size={18} />
          </button>
        </div>

        <nav className="cf-sidebar__nav">
          <div className="cf-sidebar__section-label">Platform</div>
          <ul className="cf-sidebar__list">
            {NAV_ITEMS.map(({ to, label, icon: Icon, ...rest }) => (
              <li key={to}>
                <NavLink
                  to={to}
                  end={'end' in rest ? rest.end : false}
                  onClick={onClose}
                  className={({ isActive }) =>
                    `cf-sidebar__link ${isActive ? 'cf-sidebar__link--active' : ''}`
                  }
                >
                  <Icon size={17} aria-hidden="true" />
                  <span>{label}</span>
                </NavLink>
              </li>
            ))}
          </ul>
        </nav>

        <div className="cf-sidebar__footer">
          <div className="cf-sidebar__status-card">
            <div className="cf-sidebar__status-row">
              <span className="cf-sidebar__status-label">Backend API</span>
              <StatusBadge
                label={apiStatusLabel}
                tone={apiStatusTone}
                pulse={apiStatusTone === 'healthy'}
                size="sm"
              />
            </div>
            <p className="cf-sidebar__status-meta mono">
              Phase 1 · Local Workspace
            </p>
          </div>
        </div>
      </aside>
    </>
  );
}
