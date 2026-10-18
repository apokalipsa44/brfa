import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';

import { AnalysisStage } from '../../core/models';

type StepState = 'done' | 'current' | 'upcoming' | 'failed';

/**
 * Three-step indicator: Match → Review changes → Improved CV (docs/API.md §1.1). The step that owns
 * the current stage is highlighted; a FAILED analysis marks the reached step as failed.
 *
 * <p>The steps are clickable when their result is available (`available`): the parent owns which step
 * is being viewed (`viewed`) and is notified through `stepSelected`. Every stage result is persisted
 * in the analysis snapshot, so a finished step can be re-opened at any time.
 */
@Component({
  selector: 'app-step-indicator',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './step-indicator.html',
})
export class StepIndicator {
  readonly stage = input.required<AnalysisStage>();

  /** When the analysis FAILED, the stage that failed (so the right step is marked failed). */
  readonly failedStage = input<AnalysisStage | null>(null);

  /** Index of the step whose content is currently shown (0-2). */
  readonly viewed = input(0);

  /** Number of leading steps whose result is available; indices below it are clickable. */
  readonly available = input(0);

  /** Emitted with the step index when the user picks a step with an available result. */
  readonly stepSelected = output<number>();

  private static readonly LABELS = ['Match', 'Review changes', 'Improved CV'];

  protected readonly steps = computed(() => {
    const stage = this.stage();
    const failed = stage === 'FAILED';
    const effective = failed ? this.failedStage() : stage;
    const available = this.available();
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
      return { label, state, clickable: index < available };
    });
  });

  protected select(index: number): void {
    if (index < this.available()) this.stepSelected.emit(index);
  }
}
