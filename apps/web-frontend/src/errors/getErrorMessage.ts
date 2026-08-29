import { BffProblemError, BffRequestError } from './problem';

export function getErrorMessage(error: unknown): string {
  if (error instanceof BffProblemError) return error.problem.code;
  if (error instanceof BffRequestError) return error.code;
  return 'UNKNOWN_ERROR';
}
