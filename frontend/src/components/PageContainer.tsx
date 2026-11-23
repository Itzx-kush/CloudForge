import type { ReactNode } from 'react';
import { StatusBadge, type BadgeTone } from './StatusBadge';
import './PageContainer.css';

export interface PageContainerProps {
  title: string;
  description: string;
  phaseBadge?: {
    label: string;
    tone?: BadgeTone;
  };
  actions?: ReactNode;
  children: ReactNode;
}

export function PageContainer({
  title,
  description,
  phaseBadge,
  actions,
  children,
}: PageContainerProps) {
  return (
    <div className="cf-page">
      <div className="cf-page__header">
        <div className="cf-page__heading">
          <div className="cf-page__title-row">
            <h1 className="cf-page__title">{title}</h1>
            {phaseBadge && (
              <StatusBadge
                label={phaseBadge.label}
                tone={phaseBadge.tone ?? 'planned'}
                size="sm"
              />
            )}
          </div>
          <p className="cf-page__description">{description}</p>
        </div>
        {actions && <div className="cf-page__actions">{actions}</div>}
      </div>
      <div className="cf-page__content">{children}</div>
    </div>
  );
}
