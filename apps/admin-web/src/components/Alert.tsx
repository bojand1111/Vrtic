interface AlertProps {
  readonly variant: 'error' | 'info';
  readonly title: string;
  readonly detail?: string | undefined;
  /** Secondary line, e.g. a request id for support. */
  readonly meta?: string | undefined;
}

/**
 * Accessible alert. Errors use role="alert" (assertive); informational messages use role="status".
 * All content is rendered as text, never as HTML.
 */
export function Alert({ variant, title, detail, meta }: AlertProps) {
  return (
    <div role={variant === 'error' ? 'alert' : 'status'} className={`vc-alert vc-alert--${variant}`}>
      <strong>{title}</strong>
      {detail === undefined ? null : <p>{detail}</p>}
      {meta === undefined ? null : <small>{meta}</small>}
    </div>
  );
}
