import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { AnalysisStage } from '../../core/models';

const STAGE_TEXT: Record<AnalysisStage, string> = {
  QUEUED: 'Waiting for a slot',
  EVALUATING: 'Checking your CV against the posting',
  AWAITING_REVIEW: 'Waiting for your review',
  REWRITING: 'Applying the approved changes',
  GENERATING_PDF: 'Building the PDF',
  COMPLETED: 'Done',
  FAILED: 'Failed',
};

/**
 * Calm inline progress area (docs/API.md UI): a stage line plus a thin progress bar. Never a modal
 * or a full-screen overlay — the user can keep working elsewhere in the app.
 */
@Component({
  selector: 'app-progress-inline',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="rounded-xl border border-slate-200 bg-white p-4" aria-live="polite" role="status">
      <div class="flex items-center justify-between gap-3">
        <p class="text-sm text-slate-700">{{ text() }}</p>
        <span class="text-xs text-slate-500 tabular-nums">{{ progress() }}%</span>
      </div>
      <div class="mt-3 h-1.5 overflow-hidden rounded-full bg-slate-100">
        <div
          class="h-full rounded-full bg-indigo-600 transition-all duration-200"
          [style.width.%]="progress()"
        ></div>
      </div>
    </div>
  `,
})
export class ProgressInline {
  readonly stage = input.required<AnalysisStage>();
  readonly progress = input.required<number>();

  protected readonly text = computed(
    () => STAGE_TEXT[this.stage()] ?? 'Working',
  );
}
