import { useRef } from 'react';
import { Search, X } from 'lucide-react';

export function SearchField({
  label,
  placeholder,
  value,
  onChange,
}: {
  label: string;
  placeholder: string;
  value: string;
  onChange: (value: string) => void;
}) {
  const input = useRef<HTMLInputElement>(null);
  return (
    <div className="cf-search">
      <Search size={15} aria-hidden="true" />
      <input
        ref={input}
        type="search"
        className="cf-search-input"
        aria-label={label}
        placeholder={placeholder}
        value={value}
        onChange={(e) => onChange(e.target.value)}
      />
      {value && (
        <button
          className="btn btn-ghost"
          aria-label={'Clear ' + label.toLowerCase()}
          onClick={() => {
            onChange('');
            input.current?.focus();
          }}
        >
          <X size={15} />
        </button>
      )}
    </div>
  );
}
