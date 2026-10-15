import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { AnalysisStage } from '../../core/models';

type StepState = 'done' | 'current' | 'upcoming' | 'failed';

/**
 * Three-step indicator: Match → Review changes → Improved CV (docs/API.md §1.1). The step that owns
 * the current stage is highlighted; a FAILED analysis marks the reached step as failed.
 */
@Component({
  selector: 'app-step-indicator',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ol class="flex items-center gap-2" aria-label="Analysis progress">
      @for (step of steps(); track step.label; let index = $index) {
        <li class="flex flex-1 items-center gap-2">
          <div class="flex items-center gap-2">
            <span
              class="flex h-6 w-6 shrink-0 items-center justify-center rounded-full border text-xs font-semibold"
              [class.border-indigo-600]="step.state === 'current'"
              [class.bg-indigo-600]="step.state === 'current'"
              [class.text-white]="step.state === 'current'"
              [class.border-emerald-500]="step.state === 'done'"
              [class.bg-emerald-50]="step.state === 'done'"
              [class.text-emerald-700]="step.state === 'done'"
              [class.border-red-400]="step.state === 'failed'"
              [class.bg-red-50]="step.state === 'failed'"
              [class.text-red-700]="step.state === 'failed'"
              [class.border-slate-200]="step.state === 'upcoming'"
              [class.text-slate-500]="step.state === 'upcoming'"
              [attr.aria-current]="step.state === 'current' ? 'step' : null"
            >
              @if (step.state === 'done') {
                <span aria-hidden="true">✓</span>
              } @else {
                {{ index + 1 }}
              }
            </span>
            <span
              class="text-xs font-medium whitespace-nowrap"
              [class.text-indigo-700]="step.state === 'current'"
              [class.text-emerald-700]="step.state === 'done'"
              [class.text-red-700]="step.state === 'failed'"
              [class.text-slate-500]="step.state === 'upcoming'"
            >
              {{ step.label }}
            </span>
          </div>
          @if (index < 2) {
            <span class="h-px flex-1 bg-slate-200" aria-hidden="true"></span>
          }
        </li>
      }
    </ol>
  `,
})
export class StepIndicator {
  readonly stage = input.required<AnalysisStage>();

  /** When the analysis FAILED, the stage that failed (so the right step is marked failed). */
  readonly failedStage = input<AnalysisStage | null>(null);

  private static readonly LABELS = ['Match', 'Review changes', 'Improved CV'];

  protected readonly steps = computed(() => {
    const stage = this.stage();
    const failed = stage === 'FAILED';
    const effective = failed ? this.failedStage() : stage;
    // Index of the step that owns the current stage: 0 match, 1 review, 2 improved CV.
    let progress: number;
    switch (effective) {
      case 'QUEUED':
      case 'FETCHING_POSTING':
      case 'EVALUATING':
        progress = 0;
        break;
      case 'AWAITING_REVIEW':
        progress = 1;
        break;
      case 'REWRITING':
      case 'GENERATING_PDF':
        progress = 2;
        break;
      case 'COMPLETED':
        progress = 3;
        break;
      default:
        progress = 0;
        break;
    }

    return StepIndicator.LABELS.map((label, index) => {
      let state: StepState;
      if (index < progress) state = 'done';
      else if (index === progress) state = failed ? 'failed' : 'current';
      else state = 'upcoming';
      // A failure before the review step keeps the reached step marked as failed.
      return { label, state };
    });
  });
}
