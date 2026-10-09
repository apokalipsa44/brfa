/** Job stages mirrored from the backend contract (docs/API.md). */
export type Stage =
  | 'QUEUED'
  | 'EXTRACTING_TEXT'
  | 'ANALYZING'
  | 'REWRITING'
  | 'GENERATING_PDF'
  | 'COMPLETED'
  | 'FAILED';

/** One CV job as tracked in the UI. Updated live from SSE events (plan §8.5). */
export interface Job {
  jobId: string;
  fileName: string;
  stage: Stage;
  message: string;
  progress: number;
  resultUrl?: string;
  /** Completion report (match score + changes); null while the simulated pipeline runs. */
  report?: unknown;
  errorCode?: string;
  errorMessage?: string;
  /** True while the EventSource is reconnecting (backend replays missed events). */
  reconnecting: boolean;
}

export const TERMINAL_STAGES: readonly Stage[] = ['COMPLETED', 'FAILED'];

export function isTerminal(stage: Stage): boolean {
  return stage === 'COMPLETED' || stage === 'FAILED';
}

/** Human-readable stage labels for the job card (plan §8.4). */
export const STAGE_LABELS: Record<Stage, string> = {
  QUEUED: 'Queued',
  EXTRACTING_TEXT: 'Extracting text…',
  ANALYZING: 'Analyzing…',
  REWRITING: 'Rewriting…',
  GENERATING_PDF: 'Generating PDF…',
  COMPLETED: 'Ready',
  FAILED: 'Failed',
};
