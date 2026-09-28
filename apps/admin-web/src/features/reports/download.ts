import { problemFromResponse } from '../../api/problem';
import { getCurrentLocale } from '../../i18n/locale';

/**
 * Downloads a file answer of the API (e.g. the attendance CSV) with the session cookie and hands it to the
 * browser as a file. Non-2xx answers become ApiProblem (shown by ProblemAlert).
 */
export async function downloadFile(url: string, accept: string, fileName: string): Promise<void> {
  const response = await fetch(url, {
    credentials: 'include',
    cache: 'no-store',
    headers: { Accept: `${accept}, application/problem+json`, 'Accept-Language': getCurrentLocale() },
  });
  if (!response.ok) {
    throw await problemFromResponse(response);
  }
  const blob = await response.blob();
  const href = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = href;
  link.download = fileName;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(href);
}
