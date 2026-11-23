import { AlertCircle, CheckCircle2, Info, X } from 'lucide-react';
import { createPortal } from 'react-dom';

export function Toast({
  message,
  onDismiss,
  tone = 'success',
}: {
  message: string;
  onDismiss: () => void;
  tone?: 'success' | 'error' | 'info';
}) {
  const Icon = { success: CheckCircle2, error: AlertCircle, info: Info }[tone];
  return createPortal(
    <div className="cf-toast" data-tone={tone}>
      <Icon size={18} aria-hidden="true" />
      <span role={tone === 'error' ? 'alert' : 'status'}>{message}</span>
      <button className="btn btn-ghost" onClick={onDismiss} aria-label="Dismiss notification">
        <X size={16} />
      </button>
    </div>,
    document.body,
  );
}
