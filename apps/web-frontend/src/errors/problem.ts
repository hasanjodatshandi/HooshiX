export type Problem = {
  type: string;
  title: string;
  status: number;
  code: string;
  instance?: string;
};

export class BffProblemError extends Error {
  constructor(public readonly problem: Problem) {
    super(problem.title);
    this.name = 'BffProblemError';
  }
}
