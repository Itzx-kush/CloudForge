import { AlertTriangle, RefreshCw } from 'lucide-react';
import './ErrorState.css';

export interface ErrorStateProps {
  title: string;
  message: string;
  detail?: string;
  onRetry?: () => void;
  retryLabel?: string;
  isRetrying?: boolean;
}

export function ErrorState({
  title,
  message,
  detail,
  onRetry,
  retryLabel = 'Retry check',
  isRetrying = false,
}: ErrorStateProps) {
  return (
    <div className="cf-error" role="alert">
      <div className="cf-error__icon" aria-hidden="true">
        <AlertTriangle size={20} />
      </div>
      <div className="cf-error__body">
        <h3 className="cf-error__title">{title}</h3>
        <p className="cf-error__message">{message}</p>
        {detail && <p className="cf-error__detail mono">{detail}</p>}
      </div>
      {onRetry && (
        <button
          type="button"
          className="btn cf-error__retry"
          onClick={onRetry}
          disabled={isRetrying}
        >
          <RefreshCw
            size={14}
            className={isRetrying ? 'cf-spin' : ''}
            aria-hidden="true"
          />
          <span>{isRetrying ? 'Retrying...' : retryLabel}</span>
        </button>
      )}
    </div>
  );
}
