import type { BackendHealthPayload, HealthProbeSnapshot } from '../types/health';

const HEALTH_ENDPOINT = '/api/health';
const DEFAULT_TIMEOUT_MS = 6000;

export async function fetchBackendHealth(
  signal?: AbortSignal
): Promise<HealthProbeSnapshot> {
  const startedAt = performance.now();
  const controller = new AbortController();
  const timeoutId = window.setTimeout(() => controller.abort(), DEFAULT_TIMEOUT_MS);

  const onExternalAbort = () => controller.abort();
  signal?.addEventListener('abort', onExternalAbort, { once: true });

  try {
    const response = await fetch(HEALTH_ENDPOINT, {
      method: 'GET',
      headers: {
        Accept: 'application/json',
      },
      cache: 'no-store',
      signal: controller.signal,
    });

    const clientLatencyMs = Math.max(0, Math.round(performance.now() - startedAt));
    const requestId = response.headers.get('X-Request-Id');
    const checkedAt = new Date().toISOString();

    const contentType = response.headers.get('content-type') ?? '';
    if (!contentType.includes('application/json')) {
      return {
        payload: null,
        httpStatus: response.status,
        requestId,
        clientLatencyMs,
        checkedAt,
        networkError: `Backend returned non-JSON response (HTTP ${response.status}). Ensure Spring Boot is running on the configured proxy target.`,
      };
    }

    const payload = (await response.json()) as BackendHealthPayload;
    return {
      payload,
      httpStatus: response.status,
      requestId,
      clientLatencyMs,
      checkedAt,
      networkError: null,
    };
  } catch (error) {
    const clientLatencyMs = Math.max(0, Math.round(performance.now() - startedAt));
    const isAbort = error instanceof DOMException && error.name === 'AbortError';

    return {
      payload: null,
      httpStatus: null,
      requestId: null,
      clientLatencyMs,
      checkedAt: new Date().toISOString(),
      networkError: isAbort
        ? 'Health check timed out while contacting /api/health. Verify that the Spring Boot backend is running.'
        : 'Unable to reach the Spring Boot backend via /api/health. Start the backend with Docker Compose or Maven and retry.',
    };
  } finally {
    window.clearTimeout(timeoutId);
    signal?.removeEventListener('abort', onExternalAbort);
  }
}

export { HEALTH_ENDPOINT };
