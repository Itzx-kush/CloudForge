import { useEffect, type ReactNode } from 'react';
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
  useEffect(() => {
    if (!isOpen) {
      return undefined;
    }
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onClose();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isOpen, onClose]);

  if (!isOpen) {
    return null;
  }

  return (
    <div
      className="cf-modal-backdrop"
      role="presentation"
      onClick={(event) => {
        if (event.target === event.currentTarget) {
          onClose();
        }
      }}
    >
      <div
        className="cf-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="cf-modal-title"
      >
        <div className="cf-modal__header">
          <div>
            <h2 id="cf-modal-title" className="cf-modal__title">
              {title}
            </h2>
            {subtitle && <p className="cf-modal__subtitle">{subtitle}</p>}
          </div>
          <button
            type="button"
            className="btn-ghost cf-modal__close"
            onClick={onClose}
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
