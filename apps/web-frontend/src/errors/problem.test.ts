import { describe, expect, it } from 'vitest';
import { getErrorMessage } from './getErrorMessage';
import {
  BffProblemError,
  BffRequestError,
  isUnauthorizedFailure,
  parseProblem,
} from './problem';

describe('BFF problem validation', () => {
  const valid = {
    type: 'https://errors.hooshix.example/not-authorized',
    title: 'Not authorized',
    status: 401,
    code: 'NOT_AUTHORIZED',
    instance: '/requests/example',
  };

  it('accepts a bounded problem document and preserves optional instance', () => {
    expect(parseProblem(valid)).toEqual(valid);
    expect(parseProblem({ ...valid, instance: undefined })).toEqual({
      type: valid.type,
      title: valid.title,
      status: valid.status,
      code: valid.code,
    });
  });

  it.each([
    null,
    'problem',
    { ...valid, type: 1 },
    { ...valid, type: 'x'.repeat(2049) },
    { ...valid, title: 1 },
    { ...valid, title: 'x'.repeat(201) },
    { ...valid, status: 399 },
    { ...valid, status: 600 },
    { ...valid, status: 401.5 },
    { ...valid, code: 'lowercase' },
    { ...valid, instance: 1 },
    { ...valid, instance: 'x'.repeat(2049) },
  ])('rejects malformed problem document %#', (value) => {
    expect(parseProblem(value)).toBeNull();
  });

  it('maps only structured failures and recognizes both unauthorized forms', () => {
    const problem = new BffProblemError(valid);
    const request = new BffRequestError('BFF_UNEXPECTED_RESPONSE', 401);

    expect(problem.name).toBe('BffProblemError');
    expect(request.name).toBe('BffRequestError');
    expect(getErrorMessage(problem)).toBe('NOT_AUTHORIZED');
    expect(getErrorMessage(request)).toBe('BFF_UNEXPECTED_RESPONSE');
    expect(getErrorMessage(new Error('secret detail'))).toBe('UNKNOWN_ERROR');
    expect(isUnauthorizedFailure(problem)).toBe(true);
    expect(isUnauthorizedFailure(request)).toBe(true);
    expect(isUnauthorizedFailure(new BffRequestError('BFF_NETWORK_ERROR'))).toBe(false);
    expect(isUnauthorizedFailure(new Error('ordinary'))).toBe(false);
  });
});
