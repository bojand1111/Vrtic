import { type ReactNode, useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';

interface ModalProps {
  readonly title: string;
  readonly open: boolean;
  readonly onClose: () => void;
  readonly children: ReactNode;
  /** Buttons rendered at the bottom (e.g. submit / cancel). */
  readonly footer?: ReactNode;
}

/** Native <dialog> modal: focus trap, Escape and backdrop handling come from the browser. */
export function Modal({ title, open, onClose, children, footer }: ModalProps) {
  const { t } = useTranslation();
  const ref = useRef<HTMLDialogElement | null>(null);

  useEffect(() => {
    const dialog = ref.current;
    if (dialog === null) {
      return;
    }
    // jsdom has no showModal; fall back to the open attribute there.
    if (open && !dialog.open) {
      if (typeof dialog.showModal === 'function') {
        dialog.showModal();
      } else {
        dialog.setAttribute('open', '');
      }
    } else if (!open && dialog.open) {
      if (typeof dialog.close === 'function') {
        dialog.close();
      } else {
        dialog.removeAttribute('open');
      }
    }
  }, [open]);

  return (
    <dialog
      ref={ref}
      className="vc-modal"
      aria-labelledby="vc-modal-title"
      onClose={onClose}
      onCancel={(e) => {
        e.preventDefault();
        onClose();
      }}
    >
      {open ? (
        <div className="vc-modal-body">
          <header className="vc-modal-header">
            <h2 id="vc-modal-title">{title}</h2>
            <button type="button" className="vc-button vc-button--ghost" aria-label={t('ui.close')} onClick={onClose}>
              ×
            </button>
          </header>
          {children}
          {footer === undefined ? null : <footer className="vc-modal-footer">{footer}</footer>}
        </div>
      ) : null}
    </dialog>
  );
}
