/**
 * Workspace domain types mirrored from the backend contract (docs/API.md §1-§4). The backend is the
 * source of truth: the CV and the active model are persistent context, and each analysis snapshots
 * both at creation.
 */

/** Analysis stages (docs/API.md §1.1). Stage 1 ends at AWAITING_REVIEW, stage 2 starts at REWRITING. */
export type AnalysisStage =
  | 'QUEUED'
  | 'EVALUATING'
  | 'AWAITING_REVIEW'
  | 'REWRITING'
  | 'GENERATING_PDF'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED';

export type Priority = 'HIGH' | 'MEDIUM' | 'LOW';

/** Analysis summary (docs/API.md §4): list entry, analysis.updated payload, PATCH/POST response. */
export interface AnalysisSummary {
  id: string;
  title: string;
  stage: AnalysisStage;
  score: number | null;
  /** One-or-two-sentence role summary from the evaluation; null until done (docs/API.md §6.4). */
  shortDescription: string | null;
  /** ISO local date (yyyy-MM-dd) the user applied to the job; null = not applied (docs/API.md §4). */
  appliedAt: string | null;
  progress: number;
  createdAt: string;
  updatedAt: string;
  errorCode?: string | null;
  errorMessage?: string | null;
}

/** A suggested change from stage 1 (docs/API.md §4); before/after may be empty strings. */
export interface Suggestion {
  id: string;
  section: string;
  problem: string;
  proposal: string;
  before: string;
  after: string;
  priority: Priority;
}

/** One change stage 2 actually applied to the CV. */
export interface CvChange {
  section: string;
  before: string;
  after: string;
  reason: string;
}

/** Full analysis detail (docs/API.md §4): summary plus evaluation and rewrite payloads. */
export interface AnalysisDetail extends AnalysisSummary {
  modelId: string | null;
  summary: string | null;
  matchedKeywords: string[];
  missingKeywords: string[];
  suggestions: Suggestion[];
  appliedChanges: CvChange[] | null;
  resultUrl: string | null;
}

/** Active CV info (docs/API.md §2). */
export interface CvInfo {
  id: string;
  fileName: string;
  status: string;
}

/** One LLM model LM Studio knows, with whether it is loaded right now (docs/API.md §3). */
export interface ModelInfo {
  id: string;
  loaded: boolean;
}

/** Model context (docs/API.md §3): candidates, active selection and readiness with a reason. */
export interface ModelState {
  models: ModelInfo[];
  activeModelId: string | null;
  ready: boolean;
  reason: string | null;
}

/** Maximum job posting length enforced by the backend (docs/API.md §4). */
export const MAX_JOB_POSTING_CHARS = 20_000;

/** Maximum CV size enforced by the backend (docs/API.md §2). */
export const MAX_CV_BYTES = 5 * 1024 * 1024;

export function isTerminal(stage: AnalysisStage): boolean {
  return stage === 'COMPLETED' || stage === 'FAILED';
}
