import { Loader2 } from 'lucide-react';
import './LoadingState.css';

export interface LoadingStateProps {
  title?: string;
  description?: string;
}

export function LoadingState({
  title = 'Checking platform connectivity',
  description = 'Querying GET /api/health on the Spring Boot backend...',
}: LoadingStateProps) {
  return (
    <div className="cf-loading" role="status" aria-live="polite">
      <div className="cf-loading__spinner">
        <Loader2 size={24} className="cf-spin" aria-hidden="true" />
      </div>
      <div className="cf-loading__copy">
        <p className="cf-loading__title">{title}</p>
        <p className="cf-loading__description">{description}</p>
      </div>
    </div>
  );
}
