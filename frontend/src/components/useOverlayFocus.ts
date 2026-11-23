import { useLayoutEffect, useRef } from 'react';

/** Contain keyboard focus, lock the document, and restore the invoking control. */
export function useOverlayFocus<T extends HTMLElement>(open: boolean, close: () => void) {
  const panel = useRef<T>(null);
  const closeRef = useRef(close);
  useLayoutEffect(() => {
    closeRef.current = close;
  });
  useLayoutEffect(() => {
    if (!open || !panel.current) return;
    const element = panel.current;
    const trigger = document.activeElement;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    const controls = () =>
      Array.from(
        element.querySelectorAll<HTMLElement>(
          'a[href], button:enabled, input:enabled, select:enabled, textarea:enabled, [tabindex="0"]',
        ),
      ).filter((node) => getComputedStyle(node).display !== 'none' && !node.closest('[hidden]'));
    controls()[0]?.focus();
    const frame = requestAnimationFrame(() => {
      if (!element.contains(document.activeElement)) controls()[0]?.focus();
    });
    const keyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault();
        closeRef.current();
      }
      if (e.key !== 'Tab') return;
      const items = controls();
      const first = items[0],
        last = items[items.length - 1];
      if (!element.contains(document.activeElement)) {
        e.preventDefault();
        (e.shiftKey ? last : first)?.focus();
        return;
      }
      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault();
        last?.focus();
      }
      if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault();
        first?.focus();
      }
    };
    const contain = (event: FocusEvent) => {
      if (!element.contains(event.target as Node)) controls()[0]?.focus();
    };
    document.addEventListener('keydown', keyDown);
    document.addEventListener('focusin', contain);
    return () => {
      cancelAnimationFrame(frame);
      document.removeEventListener('keydown', keyDown);
      document.removeEventListener('focusin', contain);
      document.body.style.overflow = previousOverflow;
      if (trigger instanceof HTMLElement && trigger.isConnected) trigger.focus();
    };
  }, [open]);
  return panel;
}
