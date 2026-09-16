import { useEffect } from 'react';

const BRAND = 'Vrtić Connect';

export function useDocumentTitle(title: string): void {
  useEffect(() => {
    document.title = `${title} · ${BRAND}`;
  }, [title]);
}
