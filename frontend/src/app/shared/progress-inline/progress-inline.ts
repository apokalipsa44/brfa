import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { AnalysisStage } from '../../core/models';

const STAGE_TEXT: Record<AnalysisStage, string> = {
  QUEUED: 'Waiting for a slot',
  FETCHING_POSTING: 'Fetching the posting from the link',
  EVALUATING: 'Checking your CV against the posting',
  AWAITING_REVIEW: 'Waiting for your review',
  REWRITING: 'Applying the approved changes',
  GENERATING_PDF: 'Building the PDF',
  COMPLETED: 'Done',
  FAILED: 'Failed',
  CANCELLED: 'Cancelled',
};

/**
 * Calm inline progress area (docs/API.md UI): a stage line plus a thin progress bar. Never a modal
 * or a full-screen overlay — the user can keep working elsewhere in the app.
 */
@Component({
  selector: 'app-progress-inline',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './progress-inline.html',
})
export class ProgressInline {
  readonly stage = input.required<AnalysisStage>();
  readonly progress = input.required<number>();

  /** Transient sub-stage message from the backend (docs/API.md §4); overrides the stage text. */
  readonly message = input<string | null>(null);

  protected readonly text = computed(() => this.message() ?? STAGE_TEXT[this.stage()] ?? 'Working');
}
