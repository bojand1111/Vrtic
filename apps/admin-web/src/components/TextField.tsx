import { type RefObject } from 'react';

interface TextFieldProps {
  readonly id: string;
  readonly label: string;
  readonly value: string;
  readonly onChange: (value: string) => void;
  readonly type?: 'text' | 'email' | 'password';
  readonly autoComplete?: string;
  readonly required?: boolean;
  readonly disabled?: boolean;
  readonly error?: string | undefined;
  readonly inputRef?: RefObject<HTMLInputElement | null>;
}

export function TextField({
  id,
  label,
  value,
  onChange,
  type = 'text',
  autoComplete,
  required = false,
  disabled = false,
  error,
  inputRef,
}: TextFieldProps) {
  const errorId = `${id}-error`;
  const hasError = error !== undefined;

  return (
    <div className="vc-field">
      <label htmlFor={id}>{label}</label>
      <input
        ref={inputRef}
        id={id}
        name={id}
        type={type}
        value={value}
        onChange={(event) => {
          onChange(event.target.value);
        }}
        autoComplete={autoComplete}
        required={required}
        disabled={disabled}
        aria-invalid={hasError ? 'true' : undefined}
        aria-describedby={hasError ? errorId : undefined}
      />
      {hasError ? (
        <p id={errorId} className="vc-field-error">
          {error}
        </p>
      ) : null}
    </div>
  );
}
