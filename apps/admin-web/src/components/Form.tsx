import type { ReactNode } from 'react';

/**
 * Generic controlled form fields for the business screens. Labels are always visible, errors are
 * linked with aria-describedby. Use `fieldError` (api/problem) for backend 422 messages.
 */

interface BaseProps {
  readonly id: string;
  readonly label: string;
  readonly required?: boolean;
  readonly disabled?: boolean;
  readonly error?: string | undefined;
  readonly hint?: string | undefined;
}

function Described({ id, error, hint }: { readonly id: string; readonly error?: string | undefined; readonly hint?: string | undefined }) {
  return (
    <>
      {hint === undefined ? null : (
        <small id={`${id}-hint`} className="vc-field-hint">
          {hint}
        </small>
      )}
      {error === undefined ? null : (
        <p id={`${id}-error`} className="vc-field-error">
          {error}
        </p>
      )}
    </>
  );
}

function describedBy(id: string, error?: string, hint?: string): string | undefined {
  const ids = [hint === undefined ? null : `${id}-hint`, error === undefined ? null : `${id}-error`].filter(
    (v): v is string => v !== null,
  );
  return ids.length === 0 ? undefined : ids.join(' ');
}

interface InputFieldProps extends BaseProps {
  readonly value: string;
  readonly onChange: (value: string) => void;
  readonly type?: 'text' | 'email' | 'date' | 'time' | 'number' | 'tel' | 'datetime-local';
  readonly min?: string | number;
  readonly max?: string | number;
  readonly placeholder?: string;
}

export function InputField({ id, label, value, onChange, type = 'text', required, disabled, error, hint, min, max, placeholder }: InputFieldProps) {
  return (
    <div className="vc-field">
      <label htmlFor={id}>{label}</label>
      <input
        id={id}
        name={id}
        type={type}
        value={value}
        onChange={(e) => {
          onChange(e.target.value);
        }}
        required={required}
        disabled={disabled}
        min={min}
        max={max}
        placeholder={placeholder}
        aria-invalid={error === undefined ? undefined : 'true'}
        aria-describedby={describedBy(id, error, hint)}
      />
      <Described id={id} error={error} hint={hint} />
    </div>
  );
}

export interface SelectOption {
  readonly value: string;
  readonly label: string;
}

interface SelectFieldProps extends BaseProps {
  readonly value: string;
  readonly onChange: (value: string) => void;
  readonly options: readonly SelectOption[];
  /** When given, an empty first option with this label is rendered. */
  readonly emptyLabel?: string;
}

export function SelectField({ id, label, value, onChange, options, emptyLabel, required, disabled, error, hint }: SelectFieldProps) {
  return (
    <div className="vc-field">
      <label htmlFor={id}>{label}</label>
      <select
        id={id}
        name={id}
        className="vc-select"
        value={value}
        onChange={(e) => {
          onChange(e.target.value);
        }}
        required={required}
        disabled={disabled}
        aria-invalid={error === undefined ? undefined : 'true'}
        aria-describedby={describedBy(id, error, hint)}
      >
        {emptyLabel === undefined ? null : <option value="">{emptyLabel}</option>}
        {options.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
      <Described id={id} error={error} hint={hint} />
    </div>
  );
}

interface TextAreaFieldProps extends BaseProps {
  readonly value: string;
  readonly onChange: (value: string) => void;
  readonly rows?: number;
}

export function TextAreaField({ id, label, value, onChange, rows = 4, required, disabled, error, hint }: TextAreaFieldProps) {
  return (
    <div className="vc-field">
      <label htmlFor={id}>{label}</label>
      <textarea
        id={id}
        name={id}
        rows={rows}
        value={value}
        onChange={(e) => {
          onChange(e.target.value);
        }}
        required={required}
        disabled={disabled}
        aria-invalid={error === undefined ? undefined : 'true'}
        aria-describedby={describedBy(id, error, hint)}
      />
      <Described id={id} error={error} hint={hint} />
    </div>
  );
}

interface CheckboxFieldProps {
  readonly id: string;
  readonly label: string;
  readonly checked: boolean;
  readonly onChange: (checked: boolean) => void;
  readonly disabled?: boolean;
}

export function CheckboxField({ id, label, checked, onChange, disabled }: CheckboxFieldProps) {
  return (
    <div className="vc-field vc-field--checkbox">
      <input
        id={id}
        name={id}
        type="checkbox"
        checked={checked}
        disabled={disabled}
        onChange={(e) => {
          onChange(e.target.checked);
        }}
      />
      <label htmlFor={id}>{label}</label>
    </div>
  );
}

/** Two or three fields side by side on wide screens, stacked on phones. */
export function FieldRow({ children }: { readonly children: ReactNode }) {
  return <div className="vc-field-row">{children}</div>;
}
