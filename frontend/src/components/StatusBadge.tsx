import './StatusBadge.css';

export type BadgeTone =
  | 'healthy'
  | 'degraded'
  | 'error'
  | 'info'
  | 'neutral'
  | 'planned';

export interface StatusBadgeProps {
  label: string;
  tone?: BadgeTone;
  pulse?: boolean;
  size?: 'sm' | 'md';
}

export function StatusBadge({
  label,
  tone = 'neutral',
  pulse = false,
  size = 'md',
}: StatusBadgeProps) {
  return (
    <span className={`cf-badge cf-badge--${tone} cf-badge--${size}`}>
      <span
        className={`cf-badge__dot ${pulse ? 'cf-badge__dot--pulse' : ''}`}
        aria-hidden="true"
      />
      <span>{label}</span>
    </span>
  );
}
