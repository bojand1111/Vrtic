export type HealthStatus = 'ok' | 'unavailable';

/**
 * GET /health/ready through the reverse proxy. Only the HTTP status is inspected;
 * the body is never read or displayed, so no infrastructure details can leak into the UI.
 */
export async function checkApiReady(signal?: AbortSignal): Promise<HealthStatus> {
  const init: RequestInit = { method: 'GET', credentials: 'omit', cache: 'no-store' };
  if (signal !== undefined) {
    init.signal = signal;
  }
  try {
    const response = await fetch('/health/ready', init);
    return response.ok ? 'ok' : 'unavailable';
  } catch {
    return 'unavailable';
  }
}
