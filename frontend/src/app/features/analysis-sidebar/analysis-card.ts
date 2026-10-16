import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';

import { STAGE_LABELS, isWorking } from '../../core/analyses.store';
import { relativeTime } from '../../core/format';
import { AnalysisSummary } from '../../core/models';
import { AppliedToggle } from '../../shared/applied-toggle/applied-toggle';
import { InlineTitleEdit } from '../../shared/inline-title-edit/inline-title-edit';
import { ScoreBadge } from '../../shared/score-badge/score-badge';

/**
 * One sidebar card (docs/API.md §4). Working analyses get a compact row (title or placeholder,
 * spinner, stage); finished ones get a card with the title, the evaluation's short description and a
 * status line. The select control stretches over the card; the score, the rename pencil, the delete
 * button and the applied marker live in a reserved right-hand column so they never sit under the text.
 */
@Component({
  selector: 'app-analysis-card',
  imports: [ScoreBadge, AppliedToggle, InlineTitleEdit],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { role: 'listitem' },
  template: `
    <div
      class="group relative rounded-xl border p-3.5 transition-colors duration-150"
      [class.border-indigo-500]="isSelected()"
      [class.bg-indigo-50]="isSelected()"
      [class.border-slate-200]="!isSelected()"
      [class.bg-white]="!isSelected()"
      [class.hover:border-slate-300]="!isSelected()"
    >
      <div class="flex items-stretch gap-3">
        <button
          type="button"
          class="block min-w-0 flex-1 text-left after:absolute after:inset-0 after:rounded-xl after:content-[''] focus-visible:outline-none"
          [attr.aria-current]="isSelected() ? 'true' : null"
          (click)="selected.emit(summary().id)"
        >
          @if (compact()) {
            <div class="flex items-center gap-2">
              <span
                aria-hidden="true"
                class="flex h-3.5 w-3.5 shrink-0 animate-spin rounded-full border-2 border-indigo-500 border-t-transparent"
              ></span>
              <span
                class="min-w-0 flex-1 truncate text-sm font-medium text-slate-900"
                [title]="title()"
                >{{ title() }}</span
              >
              <span class="shrink-0 text-xs text-indigo-600">{{ stageLabel() }}</span>
            </div>
          } @else {
            <span class="block truncate text-sm font-semibold text-slate-900" [title]="title()">{{
              title()
            }}</span>
            @if (summary().shortDescription) {
              <span class="mt-1 line-clamp-2 block text-sm text-slate-600">{{
                summary().shortDescription
              }}</span>
            }
            <span
              class="mt-2.5 flex min-w-0 items-center gap-1.5 text-xs"
              [class]="statusClasses()"
            >
              @if (statusGlyph()) {
                <span aria-hidden="true">{{ statusGlyph() }}</span>
              }
              <span class="truncate">{{ stageLabel() }}</span>
              <span class="text-slate-400">·</span>
              <span class="shrink-0 text-slate-500">{{ formatDate(summary().updatedAt) }}</span>
            </span>
          }
        </button>

        @if (!compact()) {
          <div class="relative z-10 flex shrink-0 flex-col items-end justify-between gap-2">
            <div class="flex items-center gap-1">
              <app-score-badge [score]="summary().score" [label]="scoreLabel()" />
              <button
                type="button"
                class="inline-flex h-7 w-7 items-center justify-center rounded-md text-slate-400 transition-colors hover:bg-slate-100 hover:text-slate-600 focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:outline-none"
                aria-label="Rename analysis"
                title="Rename"
                (click)="startRename()"
              >
                <span aria-hidden="true">✎</span>
              </button>

              <button
                type="button"
                class="inline-flex h-7 w-7 items-center justify-center rounded-md text-slate-400 transition-colors hover:bg-red-50 hover:text-red-600 focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:outline-none"
                aria-label="Delete analysis"
                title="Delete"
                (click)="deleted.emit(summary().id)"
              >
                <span aria-hidden="true">🗑</span>
              </button>
            </div>

            @if (showApplied()) {
              <app-applied-toggle
                variant="marker"
                [applied]="!!summary().appliedAt"
                [appliedAt]="summary().appliedAt"
                (toggled)="appliedChanged.emit({ id: summary().id, applied: $event })"
              />
            }
          </div>
        }
      </div>

      @if (editing()) {
        <div class="absolute inset-x-3.5 top-3.5 z-20">
          <app-inline-title-edit
            [value]="summary().title"
            [ariaLabel]="'Rename ' + title()"
            (saved)="onSaved($event)"
            (cancelled)="editing.set(false)"
          />
        </div>
      }
    </div>
  `,
})
export class AnalysisCard {
  readonly summary = input.required<AnalysisSummary>();
  readonly isSelected = input(false);

  readonly selected = output<string>();
  readonly renamed = output<{ id: string; title: string }>();
  readonly appliedChanged = output<{ id: string; applied: boolean }>();
  readonly deleted = output<string>();

  protected readonly editing = signal(false);

  protected readonly compact = computed(() => isWorking(this.summary().stage));

  /** The applied marker only makes sense on a review or a finished analysis. */
  protected readonly showApplied = computed(() => {
    const stage = this.summary().stage;
    return stage === 'AWAITING_REVIEW' || stage === 'COMPLETED';
  });

  /** Falls back to a placeholder until evaluation generates a title. */
  protected readonly title = computed(() => this.summary().title || 'Untitled analysis');

  protected readonly stageLabel = computed(
    () => STAGE_LABELS[this.summary().stage] ?? this.summary().stage,
  );

  protected readonly statusClasses = computed(() => {
    const stage = this.summary().stage;
    if (stage === 'FAILED') return 'text-red-700';
    if (stage === 'CANCELLED') return 'text-slate-500';
    if (stage === 'AWAITING_REVIEW') return 'text-indigo-700';
    if (stage === 'COMPLETED') return 'text-emerald-700';
    return 'text-indigo-600';
  });

  protected readonly statusGlyph = computed(() => {
    const stage = this.summary().stage;
    if (stage === 'COMPLETED') return '✓';
    if (stage === 'FAILED') return '!';
    if (stage === 'CANCELLED') return '×';
    return '';
  });

  protected readonly scoreLabel = computed(() => {
    const score = this.summary().score;
    return score === null ? null : `Match score ${score} out of 100`;
  });

  protected startRename(): void {
    this.editing.set(true);
  }

  protected onSaved(title: string): void {
    this.editing.set(false);
    if (title !== this.summary().title) this.renamed.emit({ id: this.summary().id, title });
  }

  protected readonly formatDate = relativeTime;
}
