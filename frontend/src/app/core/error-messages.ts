import { HttpErrorResponse } from '@angular/common/http';

/** Friendly, action-oriented copy for the backend's stable error codes (docs/API.md §5). */
const MESSAGES: Record<string, string> = {
  MISSING_FILE: 'Choose a PDF file to upload.',
  FILE_TOO_LARGE: 'The PDF is larger than 5 MB. Choose a smaller file.',
  INVALID_PDF: 'That file is not a PDF. Choose a PDF file.',
  PDF_NO_TEXT: 'The PDF has no extractable text. It looks like a scan.',
  PDF_UNREADABLE: 'The PDF could not be read. It may be encrypted or corrupted.',
  CV_NOT_FOUND: 'Upload a CV to start.',
  CV_MISSING: 'Upload a CV to start.',
  MODEL_NOT_READY: 'Load a model in LM Studio to continue.',
  MODEL_BLANK: 'Choose a model.',
  MODEL_NOT_FOUND: 'That model is not available in LM Studio.',
  LLM_UNREACHABLE: 'LM Studio is not reachable. Start the server and try again.',
  POSTING_REQUIRED: 'Paste a job posting.',
  MISSING_JOB_DESCRIPTION: 'Paste a job posting.',
  JOB_DESCRIPTION_TOO_LONG: 'The job posting is too long.',
  POSTING_SOURCE_CONFLICT: 'Provide either a posting or a link, not both.',
  URL_INVALID: 'That link is not a valid public URL. Paste the posting text instead.',
  URL_BLOCKED: 'That address is not publicly reachable. Paste the posting text instead.',
  FETCH_FAILED: 'The page could not be fetched. Paste the posting text instead.',
  FETCH_NOT_HTML: 'That link does not point to a web page. Paste the posting text instead.',
  POSTING_NOT_FOUND:
    'No job posting could be found on that page (it may need a login). Paste the posting text instead.',
  ANALYSIS_NOT_FOUND: 'This analysis no longer exists.',
  TITLE_BLANK: 'Enter a title.',
  DESCRIPTION_TOO_LONG: 'The description is too long (200 characters max).',
  NO_UPDATES: 'Nothing to update.',
  APPLIED_AT_INVALID: 'Enter a valid applied date.',
  APPLIED_AT_FUTURE: 'The applied date cannot be in the future.',
  NOT_AWAITING_REVIEW: 'This analysis is no longer waiting for review.',
  NO_SUGGESTIONS_APPROVED: 'Select at least one change to apply.',
  NOT_FAILED: 'This analysis is not in a failed state.',
  NOT_RETRYABLE: 'This analysis can only be retried after a failure or cancellation.',
  NOT_RERUNNABLE: 'This analysis can only be checked again once it is a finished link analysis.',
  NOT_CANCELLABLE: 'This analysis is no longer running.',
  NOT_READY: 'The improved CV is not ready yet.',
  RESULT_NOT_FOUND: 'The improved CV file is no longer available.',
  GAP_TEXT_BLANK: 'Enter the skill gap text.',
  GAP_TEXT_TOO_LONG: 'The skill gap is too long (300 characters max).',
  GAP_NOT_FOUND: 'This skill gap no longer exists.',
  REBUILD_RUNNING: 'Regrouping is already in progress. Try again in a moment.',
  CLUSTER_LABEL_BLANK: 'Enter a skill label.',
  CLUSTER_LABEL_TOO_LONG: 'The skill label is too long (120 characters max).',
  CLUSTER_NOT_FOUND: 'This grouped skill no longer exists.',
  IMPROVEMENT_RUNNING: 'A CV build is already running. Wait for it to finish.',
  IMPROVEMENT_SKILLS_EMPTY: 'Select at least one skill to add to the CV.',
  IMPROVEMENT_SKILL_TOO_LONG: 'One of the selected skills is too long (120 characters max).',
  IMPROVEMENT_TOO_MANY_SKILLS: 'Select at most 30 skills.',
  LLM_INVALID_OUTPUT: 'The model returned an invalid response. Try again.',
  LLM_ABORTED: 'The model was switched while this analysis ran. Try again.',
  INTERRUPTED: 'The backend restarted while this analysis was running. Retry to continue.',
  INTERNAL_ERROR: 'Something went wrong. Try again.',
  BAD_REQUEST: 'The request was rejected. Check the input.',
  NOT_FOUND: 'That resource no longer exists.',
  METHOD_NOT_ALLOWED: 'That action is not allowed.',
  UNSUPPORTED_MEDIA_TYPE: 'Unsupported file type.',
  REQUEST_REJECTED: 'The request was rejected. Try again.',
};

export function errorMessage(
  code?: string | null,
  fallback = 'Something went wrong. Try again.',
): string {
  if (code && MESSAGES[code]) return MESSAGES[code];
  return fallback;
}

/** Maps any HTTP failure to friendly text, preferring the backend's ProblemDetail (docs/API.md §1.2). */
export function problemMessage(
  err: unknown,
  fallback = 'Something went wrong. Try again.',
): string {
  if (err instanceof HttpErrorResponse) {
    const body = err.error as { code?: unknown; detail?: unknown } | null;
    if (body && typeof body.code === 'string') return errorMessage(body.code, fallback);
    if (err.status === 0) return 'Cannot reach the backend. Is it running?';
    return fallback;
  }
  return fallback;
}
