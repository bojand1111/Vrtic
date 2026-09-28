/**
 * RFC 9457 "Problem Details" support. The backend answers every error with
 * `application/problem+json`; this module turns such responses into a typed error.
 */
/** One entry of `errors` on a 422 validation problem. */
export interface FieldProblem {
  readonly field: string;
  readonly code: string;
  readonly message: string;
}

export interface ProblemDocument {
  readonly type: string;
  readonly title: string;
  readonly status: number;
  readonly detail?: string;
  readonly instance?: string;
  readonly requestId?: string;
  readonly errors?: readonly FieldProblem[];
  /** Present on 409 version conflicts. */
  readonly currentVersion?: number;
}

export const PROBLEM_JSON_MEDIA_TYPE = 'application/problem+json';

export class ApiProblem extends Error {
  override readonly name = 'ApiProblem';
  readonly type: string;
  readonly title: string;
  readonly status: number;
  readonly detail: string | undefined;
  readonly instance: string | undefined;
  readonly requestId: string | undefined;
  readonly errors: readonly FieldProblem[];
  readonly currentVersion: number | undefined;

  constructor(doc: ProblemDocument) {
    super(doc.detail === undefined ? doc.title : `${doc.title}: ${doc.detail}`);
    this.type = doc.type;
    this.title = doc.title;
    this.status = doc.status;
    this.detail = doc.detail;
    this.instance = doc.instance;
    this.requestId = doc.requestId;
    this.errors = doc.errors ?? [];
    this.currentVersion = doc.currentVersion;
  }
}

/** Thrown when the request never produced an HTTP response (offline, DNS, CORS, proxy down). */
export class NetworkError extends Error {
  override readonly name = 'NetworkError';

  constructor(options?: ErrorOptions) {
    super('Network request failed', options);
  }
}

export function isApiProblem(value: unknown): value is ApiProblem {
  return value instanceof ApiProblem;
}

export function isProblemJsonContentType(contentType: string | null): boolean {
  if (contentType === null) {
    return false;
  }
  const mediaType = contentType.split(';', 1)[0]?.trim().toLowerCase() ?? '';
  return mediaType === PROBLEM_JSON_MEDIA_TYPE || mediaType === 'application/json';
}

function optionalString(value: unknown): string | undefined {
  return typeof value === 'string' && value.length > 0 ? value : undefined;
}

/**
 * Structural check for a problem document. `title` and `status` are required;
 * `type` defaults to "about:blank" as the RFC prescribes.
 */
export function isProblemDocument(value: unknown): value is ProblemDocument {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const candidate = value as Record<string, unknown>;
  return typeof candidate.title === 'string' && typeof candidate.status === 'number';
}

function isFieldProblem(value: unknown): value is FieldProblem {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const c = value as Record<string, unknown>;
  return typeof c.field === 'string' && typeof c.code === 'string' && typeof c.message === 'string';
}

function normalizeProblem(raw: ProblemDocument, fallbackStatus: number): ProblemDocument {
  const detail = optionalString(raw.detail);
  const instance = optionalString(raw.instance);
  const requestId = optionalString(raw.requestId);
  const errors = Array.isArray(raw.errors) ? raw.errors.filter(isFieldProblem) : [];
  const currentVersion = typeof raw.currentVersion === 'number' ? raw.currentVersion : undefined;
  return {
    type: optionalString(raw.type) ?? 'about:blank',
    title: raw.title,
    status: Number.isInteger(raw.status) ? raw.status : fallbackStatus,
    ...(detail === undefined ? {} : { detail }),
    ...(instance === undefined ? {} : { instance }),
    ...(requestId === undefined ? {} : { requestId }),
    ...(errors.length === 0 ? {} : { errors }),
    ...(currentVersion === undefined ? {} : { currentVersion }),
  };
}

/**
 * Builds an {@link ApiProblem} from a non-2xx response. Falls back to a generic
 * problem when the body is not a problem document (HTML error page, empty body, malformed JSON).
 */
export async function problemFromResponse(response: Response): Promise<ApiProblem> {
  let doc: ProblemDocument | null = null;

  if (isProblemJsonContentType(response.headers.get('content-type'))) {
    try {
      const body: unknown = await response.json();
      if (isProblemDocument(body)) {
        doc = normalizeProblem(body, response.status);
      }
    } catch {
      // Malformed JSON: fall through to the generic problem below.
    }
  }

  return new ApiProblem(
    doc ?? {
      type: 'about:blank',
      title: response.statusText.length > 0 ? response.statusText : `HTTP ${response.status}`,
      status: response.status,
    },
  );
}

/** Backend 422 message for `field` (first match), for the `error` prop of a form field. */
export function fieldError(error: unknown, field: string): string | undefined {
  if (!(error instanceof ApiProblem)) {
    return undefined;
  }
  const match = error.errors.find((e) => e.field === field);
  return match === undefined ? undefined : match.message;
}
