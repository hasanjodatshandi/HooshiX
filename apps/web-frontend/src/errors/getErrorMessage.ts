import { BffProblemError } from './problem';

export function getErrorMessage(error: unknown): string {
  if (error instanceof BffProblemError) return error.problem.code;
  return 'UNKNOWN_ERROR';
}
