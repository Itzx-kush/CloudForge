import { useOverlayFocus } from './useOverlayFocus';
import { useId, useRef, type ReactNode } from 'react';
import { X } from 'lucide-react';
import './Modal.css';

export interface ModalProps {
  isOpen: boolean;
  title: string;
  subtitle?: string;
  onClose: () => void;
  children: ReactNode;
  footer?: ReactNode;
}

export function Modal({
  isOpen,
  title,
  subtitle,
  onClose,
  children,
  footer,
}: ModalProps) {
  const closing = useRef(false);
  const ref = useOverlayFocus<HTMLDivElement>(isOpen, dismiss);
  function dismiss() {
    if (closing.current) return;
    const panel = ref.current;
    const reduced = document.documentElement.dataset.motion === 'reduced' ||
      window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
    if (!panel?.animate || reduced) { onClose(); return; }
    closing.current = true;
    const exit = panel.animate([{ opacity: 1 }, { opacity: 0, transform: 'translateY(3px)' }], { duration: 100 });
    void exit.finished.then(() => {
      closing.current = false;
      if (panel.isConnected) onClose();
    }, () => { closing.current = false; });
  }
  const titleId = useId();

  if (!isOpen) {
    return null;
  }

  return (
    <div
      className="cf-modal-backdrop"
      role="presentation"
      onClick={(event) => {
        if (event.target === event.currentTarget) {
          dismiss();
        }
      }}
    >
      <div
        className="cf-modal"
        role="dialog"
        ref={ref}
        aria-modal="true"
        aria-labelledby={titleId}
      >
        <div className="cf-modal__header">
          <div>
            <h2 id={titleId} className="cf-modal__title">
              {title}
            </h2>
            {subtitle && <p className="cf-modal__subtitle">{subtitle}</p>}
          </div>
          <button
            type="button"
            className="btn-ghost cf-modal__close"
            onClick={dismiss}
            aria-label="Close dialog"
          >
            <X size={18} />
          </button>
        </div>
        <div className="cf-modal__body">{children}</div>
        {footer && <div className="cf-modal__footer">{footer}</div>}
      </div>
    </div>
  );
}
