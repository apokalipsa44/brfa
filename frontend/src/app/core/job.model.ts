/** Job stages mirrored from the backend contract (docs/API.md). */
export type Stage =
  | 'QUEUED'
  | 'EXTRACTING_TEXT'
  | 'ANALYZING'
  | 'REWRITING'
  | 'GENERATING_PDF'
  | 'COMPLETED'
  | 'FAILED';

/** One change the rewrite step applied to a CV section (docs/API.md report shape). */
export interface CvChange {
  section: string;
  before: string;
  after: string;
  reason: string;
}

export interface Suggestion {
  section: string;
  problem: string;
  recommendation: string;
  priority: 'HIGH' | 'MEDIUM' | 'LOW';
}

/** Completion report attached to the `completed` event and the snapshot endpoint. */
export interface JobReport {
  matchScoreBefore: number;
  matchedKeywords: string[];
  missingKeywords: string[];
  suggestions: Suggestion[];
  summary: string;
  changes: CvChange[];
}

/** One CV job as tracked in the UI. Updated live from SSE events (plan §8.5). */
export interface Job {
  jobId: string;
  fileName: string;
  /** Model selected for this job (may be empty when the backend default was used). */
  model?: string;
  stage: Stage;
  message: string;
  progress: number;
  resultUrl?: string;
  /** Completion report; null while the pipeline runs or the simulated pipeline is active. */
  report?: JobReport | null;
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