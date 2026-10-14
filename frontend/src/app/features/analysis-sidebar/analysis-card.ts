import { ChangeDetectionStrategy, Component, input, output, signal } from '@angular/core';

import { relativeTime } from '../../core/format';
import { AnalysisSummary } from '../../core/models';
import { STAGE_LABELS, isWorking } from '../../core/analyses.store';
import { ScoreBadge } from '../../shared/score-badge/score-badge';

/**
 * One compact analysis card (docs/API.md §4): title (truncated), status with a relative time and the
 * match score once evaluated. The selected card is highlighted; a small menu renames and deletes.
 */
@Component({
  selector: 'app-analysis-card',
  imports: [ScoreBadge],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <li
      class="rounded-xl border p-2.5 transition-colors duration-150"
      [class.border-indigo-500]="isSelected()"
      [class.bg-indigo-50]="isSelected()"
      [class.border-slate-200]="!isSelected()"
      [class.bg-white]="!isSelected()"
      [class.hover:border-slate-300]="!isSelected()"
    >
      <div class="flex items-start gap-2">
        <button
          type="button"
          class="min-w-0 flex-1 text-left"
          [attr.aria-current]="isSelected() ? 'true' : null"
          (click)="selected.emit(summary().id)"
        >
          <div class="flex items-start justify-between gap-2">
            <h3 class="min-w-0 flex-1 truncate text-sm font-medium text-slate-900" [title]="summary().title">
              {{ summary().title }}
            </h3>
            <app-score-badge [score]="summary().score" />
          </div>
          <p class="mt-1 flex items-center gap-1.5 text-xs" [class]="statusClasses()">
            @if (working()) {
              <span
                aria-hidden="true"
                class="flex h-3 w-3 animate-spin rounded-full border-2 border-current border-t-transparent"
              ></span>
            } @else {
              <span aria-hidden="true" class="flex h-3 w-3 items-center justify-center">{{ statusGlyph() }}</span>
            }
            <span [attr.aria-live]="working() ? 'polite' : null">{{ stageLabel() }}</span>
            <span class="text-slate-400">·</span>
            <span class="text-slate-400">{{ formatDate(summary().updatedAt) }}</span>
          </p>
        </button>

        <div class="relative shrink-0">
          <button
            type="button"
            class="rounded-md p-1 text-slate-400 hover:bg-slate-100 hover:text-slate-700 focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:outline-none"
            aria-label="Actions for analysis"
            [attr.aria-expanded]="menuOpen()"
            (click)="menuOpen.set(!menuOpen())"
            (keydown.escape)="menuOpen.set(false)"
          >
            <span aria-hidden="true">⋯</span>
          </button>

          @if (menuOpen()) {
            <button
              type="button"
              class="fixed inset-0 z-10 cursor-default"
              aria-label="Close actions menu"
              (click)="menuOpen.set(false)"
            ></button>
            <div
              class="absolute right-0 z-20 mt-1 w-36 overflow-hidden rounded-lg border border-slate-200 bg-white py-1 shadow-lg"
            >
              <button
                type="button"
                class="block w-full px-3 py-1.5 text-left text-sm text-slate-700 hover:bg-slate-50"
                (click)="renaming.emit(summary()); menuOpen.set(false)"
              >
                Rename
              </button>
              <button
                type="button"
                class="block w-full px-3 py-1.5 text-left text-sm text-red-700 hover:bg-red-50"
                (click)="deleted.emit(summary().id); menuOpen.set(false)"
              >
                Delete
              </button>
            </div>
          }
        </div>
      </div>
    </li>
  `,
})
export class AnalysisCard {
  readonly summary = input.required<AnalysisSummary>();
  readonly isSelected = input(false);

  readonly selected = output<string>();
  readonly renaming = output<AnalysisSummary>();
  readonly deleted = output<string>();

  protected readonly menuOpen = signal(false);

  protected readonly working = () => isWorking(this.summary().stage);

  protected readonly stageLabel = () => STAGE_LABELS[this.summary().stage] ?? this.summary().stage;

  protected statusClasses(): string {
    const stage = this.summary().stage;
    if (stage === 'FAILED') return 'text-red-700';
    if (stage === 'AWAITING_REVIEW') return 'text-indigo-700';
    if (stage === 'COMPLETED') return 'text-emerald-700';
    return 'text-indigo-600';
  }

  protected statusGlyph(): string {
    const stage = this.summary().stage;
    if (stage === 'COMPLETED') return '✓';
    if (stage === 'FAILED') return '!';
    return '';
  }

  protected formatDate = relativeTime;
}
