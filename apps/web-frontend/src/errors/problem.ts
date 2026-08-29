export type Problem = {
  type: string;
  title: string;
  status: number;
  code: string;
  instance?: string;
};

const PROBLEM_CODE = /^[A-Z][A-Z0-9_]{0,63}$/;

export function parseProblem(value: unknown): Problem | null {
  if (!value || typeof value !== 'object') return null;
  const candidate = value as Record<string, unknown>;
  if (
    typeof candidate.type !== 'string'
    || candidate.type.length > 2048
    || typeof candidate.title !== 'string'
    || candidate.title.length > 200
    || !Number.isInteger(candidate.status)
    || (candidate.status as number) < 400
    || (candidate.status as number) > 599
    || typeof candidate.code !== 'string'
    || !PROBLEM_CODE.test(candidate.code)
    || (candidate.instance !== undefined
      && (typeof candidate.instance !== 'string' || candidate.instance.length > 2048))
  ) {
    return null;
  }
  return {
    type: candidate.type,
    title: candidate.title,
    status: candidate.status as number,
    code: candidate.code,
    ...(typeof candidate.instance === 'string' ? { instance: candidate.instance } : {}),
  };
}

export class BffProblemError extends Error {
  constructor(public readonly problem: Problem) {
    super(problem.code);
    this.name = 'BffProblemError';
  }
}

export type BffRequestFailureCode =
  | 'BFF_INVALID_RESPONSE'
  | 'BFF_NETWORK_ERROR'
  | 'BFF_REQUEST_CANCELLED'
  | 'BFF_REQUEST_TIMEOUT'
  | 'BFF_UNEXPECTED_RESPONSE';

export class BffRequestError extends Error {
  constructor(
    public readonly code: BffRequestFailureCode,
    public readonly status?: number,
  ) {
    super(code);
    this.name = 'BffRequestError';
  }
}

export function isUnauthorizedFailure(error: unknown): boolean {
  return error instanceof BffProblemError
    ? error.problem.status === 401
    : error instanceof BffRequestError && error.status === 401;
}
