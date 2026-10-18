/**
 * Workspace domain types mirrored from the backend contract (docs/API.md §1-§4). The backend is the
 * source of truth: the CV and the active model are persistent context, and each analysis snapshots
 * both at creation.
 */

/** Analysis stages (docs/API.md §1.1). Stage 1 ends at AWAITING_REVIEW, stage 2 starts at REWRITING. */
export type AnalysisStage =
  | 'QUEUED'
  | 'FETCHING_POSTING'
  | 'EVALUATING'
  | 'AWAITING_REVIEW'
  | 'REWRITING'
  | 'GENERATING_PDF'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED';

export type Priority = 'HIGH' | 'MEDIUM' | 'LOW';

/** How important a posting requirement is (docs/API.md §4). */
export type RequirementType = 'MUST' | 'NICE' | 'SOFT';

/** How well the CV covers a posting requirement (docs/API.md §4). */
export type RequirementStatus = 'MET' | 'PARTIAL' | 'MISSING';

/** One posting requirement and how the CV covers it (docs/API.md §4). */
export interface Requirement {
  text: string;
  type: RequirementType;
  status: RequirementStatus;
  /** Verbatim CV quote for MET/PARTIAL requirements; empty string for MISSING. */
  cvEvidence: string;
}

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
  /** Transient sub-stage message during FETCHING_POSTING, null otherwise (docs/API.md §4). */
  stageMessage?: string | null;
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
  /** True when the proposed text mentions words absent from the CV; the UI leaves it unchecked. */
  needsConfirmation: boolean;
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
  /** The link the user provided, or null for a pasted posting (docs/API.md §4). */
  sourceUrl: string | null;
  summary: string | null;
  requirements: Requirement[];
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

/** Whether a skill gap is a missing requirement or a nice-to-have (docs/API.md §9). */
export type GapKind = 'MISSING' | 'NICE';

/** The user's progress on a skill gap (docs/API.md §9). */
export type GapStatus = 'OPEN' | 'PLANNED' | 'LEARNED';

/** Where a skill gap came from: aggregated from analyses or added by the user (docs/API.md §9). */
export type GapOrigin = 'DERIVED' | 'MANUAL';

/** One analysis that raised a skill gap (docs/API.md §9). */
export interface GapSource {
  analysisId: string;
  title: string;
}

/** One aggregated + curated skill gap (docs/API.md §9). */
export interface GapItem {
  id: string;
  text: string;
  kind: GapKind;
  status: GapStatus;
  note: string;
  origin: GapOrigin;
  occurrences: number;
  sources: GapSource[];
  createdAt: string;
  updatedAt: string;
}

/** Maximum skill-gap text length enforced by the backend (docs/API.md §9). */
export const MAX_GAP_TEXT_CHARS = 300;

/** Maximum skill-gap note length enforced by the backend (docs/API.md §9). */
export const MAX_GAP_NOTE_CHARS = 500;

/** Maximum job posting length enforced by the backend (docs/API.md §4). */
export const MAX_JOB_POSTING_CHARS = 20_000;

/** Maximum CV size enforced by the backend (docs/API.md §2). */
export const MAX_CV_BYTES = 5 * 1024 * 1024;
