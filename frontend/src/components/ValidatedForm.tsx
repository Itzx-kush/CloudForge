import { useId, useState, type FormHTMLAttributes } from 'react';

/** Preserve native constraints and present validation feedback inside the form. */
export function ValidatedForm({
  children,
  onSubmit,
  ...props
}: FormHTMLAttributes<HTMLFormElement>) {
  const [message, setMessage] = useState('');
  const errorId = useId();
  return (
    <form
      {...props}
      noValidate
      onInput={() => setMessage('')}
      onSubmit={(event) => {
        const field = event.currentTarget.querySelector<
          HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement
        >(':invalid');
        if (!field) {
          setMessage('');
          onSubmit?.(event);
          return;
        }
        event.preventDefault();
        setMessage(
          [field.labels?.[0]?.textContent?.trim(), field.validationMessage]
            .filter(Boolean)
            .join(' — '),
        );
        field.setAttribute('aria-invalid', 'true');
        field.setAttribute('aria-errormessage', errorId);
        field.addEventListener(
          'input',
          () => {
            field.removeAttribute('aria-invalid');
            field.removeAttribute('aria-errormessage');
          },
          { once: true },
        );
        field.focus();
      }}
    >
      {message && (
        <p className="cf-field__error" role="alert" id={errorId}>
          {message}
        </p>
      )}
      {children}
    </form>
  );
}
