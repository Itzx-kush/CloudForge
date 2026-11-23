import type { ReactNode } from 'react';
import './DashboardCard.css';

export interface DashboardCardProps {
  title: string;
  subtitle?: string;
  icon?: ReactNode;
  headerAction?: ReactNode;
  highlight?: boolean;
  className?: string;
  children: ReactNode;
}

export function DashboardCard({
  title,
  subtitle,
  icon,
  headerAction,
  highlight = false,
  className = '',
  children,
}: DashboardCardProps) {
  return (
    <section
      className={`cf-card ${highlight ? 'cf-card--highlight' : ''} ${className}`.trim()}
    >
      <header className="cf-card__header">
        <div className="cf-card__title-group">
          {icon && (
            <div className="cf-card__icon" aria-hidden="true">
              {icon}
            </div>
          )}
          <div>
            <h2 className="cf-card__title">{title}</h2>
            {subtitle && <p className="cf-card__subtitle">{subtitle}</p>}
          </div>
        </div>
        {headerAction && <div className="cf-card__action">{headerAction}</div>}
      </header>
      <div className="cf-card__body">{children}</div>
    </section>
  );
}
