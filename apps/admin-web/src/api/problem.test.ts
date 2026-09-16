import { describe, expect, it } from 'vitest';

import { ApiProblem, isProblemDocument, isProblemJsonContentType, problemFromResponse } from './problem';

function response(status: number, body: string | null, contentType?: string): Response {
  const headers = new Headers();
  if (contentType !== undefined) {
    headers.set('Content-Type', contentType);
  }
  return new Response(body, { status, headers });
}

describe('problemFromResponse', () => {
  it('parses application/problem+json into a typed ApiProblem', async () => {
    const res = response(
      501,
      JSON.stringify({
        type: 'https://vrtic-connect.example/problems/not-implemented',
        title: 'Not Implemented',
        status: 501,
        detail: 'Login is not implemented yet.',
        requestId: 'req-123',
      }),
      'application/problem+json; charset=utf-8',
    );

    const problem = await problemFromResponse(res);

    expect(problem).toBeInstanceOf(ApiProblem);
    expect(problem).toBeInstanceOf(Error);
    expect(problem.name).toBe('ApiProblem');
    expect(problem.type).toBe('https://vrtic-connect.example/problems/not-implemented');
    expect(problem.title).toBe('Not Implemented');
    expect(problem.status).toBe(501);
    expect(problem.detail).toBe('Login is not implemented yet.');
    expect(problem.requestId).toBe('req-123');
    expect(problem.message).toBe('Not Implemented: Login is not implemented yet.');
  });

  it('defaults type to about:blank and drops empty optional fields', async () => {
    const res = response(400, JSON.stringify({ title: 'Bad Request', status: 400, detail: '' }), 'application/problem+json');

    const problem = await problemFromResponse(res);

    expect(problem.type).toBe('about:blank');
    expect(problem.detail).toBeUndefined();
    expect(problem.requestId).toBeUndefined();
  });

  it('falls back to a generic problem when the body is not a problem document', async () => {
    const res = response(502, '<html>Bad Gateway</html>', 'text/html');

    const problem = await problemFromResponse(res);

    expect(problem).toBeInstanceOf(ApiProblem);
    expect(problem.status).toBe(502);
    expect(problem.type).toBe('about:blank');
    expect(problem.title).toBe('HTTP 502');
    expect(problem.detail).toBeUndefined();
  });

  it('falls back when problem+json body is malformed', async () => {
    const res = response(500, '{not json', 'application/problem+json');

    const problem = await problemFromResponse(res);

    expect(problem.status).toBe(500);
    expect(problem.title).toBe('HTTP 500');
  });
});

describe('isProblemDocument', () => {
  it('requires a string title and numeric status', () => {
    expect(isProblemDocument({ title: 'x', status: 400 })).toBe(true);
    expect(isProblemDocument({ title: 'x' })).toBe(false);
    expect(isProblemDocument({ status: 400 })).toBe(false);
    expect(isProblemDocument(null)).toBe(false);
    expect(isProblemDocument('nope')).toBe(false);
  });
});

describe('isProblemJsonContentType', () => {
  it('accepts problem+json and plain json, ignoring parameters and case', () => {
    expect(isProblemJsonContentType('application/problem+json')).toBe(true);
    expect(isProblemJsonContentType('Application/Problem+JSON; charset=utf-8')).toBe(true);
    expect(isProblemJsonContentType('application/json')).toBe(true);
    expect(isProblemJsonContentType('text/html')).toBe(false);
    expect(isProblemJsonContentType(null)).toBe(false);
  });
});
