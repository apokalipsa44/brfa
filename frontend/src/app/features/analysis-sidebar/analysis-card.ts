import { ChangeDetectionStrategy, Component, input, output, signal } from '@angular/core';

import { STAGE_LABELS, isWorking } from '../../core/analyses.store';
import { relativeTime } from '../../core/format';
import { AnalysisSummary } from '../../core/models';
import { AppliedToggle } from '../../shared/applied-toggle/applied-toggle';
import { InlineTitleEdit } from '../../shared/inline-title-edit/inline-title-edit';
import { ScoreBadge } from '../../shared/score-badge/score-badge';

/**
 * One sidebar card (docs/API.md §4). Working analyses get a compact row (title or placeholder,
 * spinner, stage); finished ones get a three-row card: title with an inline rename pencil, the
 * evaluation's short description, and a status line with the score and the "applied to job" marker.
 * The whole card selects via a stretched link; the pencil, the marker and the actions menu sit above.
 */
@Component({
  selector: 'app-analysis-card',
  imports: [ScoreBadge, AppliedToggle, InlineTitleEdit],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { role: 'listitem' },
  template: `
    <div
      class="group relative rounded-xl border p-3 transition-colors duration-150"
      [class.border-indigo-500]="isSelected()"
      [class.bg-indigo-50]="isSelected()"
      [class.border-slate-200]="!isSelected()"
      [class.bg-white]="!isSelected()"
      [class.hover:border-slate-300]="!isSelected()"
    >
      <button
        type="button"
        class="block w-full text-left after:absolute after:inset-0 after:rounded-xl after:content-[''] focus-visible:outline-none"
        [attr.aria-current]="isSelected() ? 'true' : null"
        (click)="selected.emit(summary().id)"
      >
        @if (compact()) {
          <div class="flex items-center gap-2">
            <span
              aria-hidden="true"
              class="flex h-3.5 w-3.5 shrink-0 animate-spin rounded-full border-2 border-indigo-500 border-t-transparent"
            ></span>
            <span class="min-w-0 flex-1 truncate text-sm font-medium text-slate-900" [title]="title()">{{
              title()
            }}</span>
            <span class="shrink-0 text-xs text-indigo-600">{{ stageLabel() }}</span>
          </div>
        } @else {
          <span class="block truncate text-sm font-medium text-slate-900" [title]="title()">{{ title() }}</span>

          @if (summary().shortDescription) {
            <span class="mt-1 line-clamp-2 block text-xs text-slate-500">{{ summary().shortDescription }}</span>
          }

          <span class="mt-1.5 flex items-center justify-between gap-2 text-xs">
            <span class="flex min-w-0 items-center gap-1.5" [class]="statusClasses()">
              <span aria-hidden="true" class="flex h-3 w-3 items-center justify-center">{{ statusGlyph() }}</span>
              <span class="truncate">{{ stageLabel() }}</span>
              <span class="text-slate-400">·</span>
              <span class="shrink-0 text-slate-500">{{ formatDate(summary().updatedAt) }}</span>
            </span>
            <span class="flex shrink-0 items-center gap-1.5">
              <app-score-badge [score]="summary().score" [label]="scoreLabel()" />
              @if (showApplied()) {
                <app-applied-toggle
                  variant="marker"
                  [applied]="summary().appliedAt !== null"
                  [appliedAt]="summary().appliedAt"
                  (toggled)="appliedChanged.emit({ id: summary().id, applied: $event })"
                />
              }
            </span>
          </span>
        }
      </button>

      @if (!compact()) {
        <div class="absolute right-1.5 top-1.5 z-10 flex items-center gap-0.5 opacity-0 group-hover:opacity-100 focus-within:opacity-100">
          <button
            type="button"
            class="inline-flex h-8 w-8 items-center justify-center rounded-md text-slate-500 hover:bg-slate-100 hover:text-slate-700 focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:outline-none"
            aria-label="Rename analysis"
            title="Rename"
            (click)="startRename()"
          >
            <span aria-hidden="true">✎</span>
          </button>

          <div class="relative">
            <button
              type="button"
              class="inline-flex h-8 w-8 items-center justify-center rounded-md text-slate-500 hover:bg-slate-100 hover:text-slate-700 focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:outline-none"
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
                  class="block w-full px-3 py-1.5 text-left text-sm text-red-700 hover:bg-red-50"
                  (click)="deleted.emit(summary().id); menuOpen.set(false)"
                >
                  Delete
                </button>
              </div>
            }
          </div>
        </div>
      }

      @if (editing()) {
        <div class="absolute inset-x-3 top-3 z-20">
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

  protected readonly menuOpen = signal(false);
  protected readonly editing = signal(false);

  protected readonly compact = () => isWorking(this.summary().stage);

  /** The applied marker only makes sense on a review or a finished analysis. */
  protected readonly showApplied = () => {
    const stage = this.summary().stage;
    return stage === 'AWAITING_REVIEW' || stage === 'COMPLETED';
  };

  /** Falls back to a placeholder until evaluation generates a title. */
  protected title(): string {
    return this.summary().title || 'Untitled analysis';
  }

  protected readonly stageLabel = () => STAGE_LABELS[this.summary().stage] ?? this.summary().stage;

  protected statusClasses(): string {
    const stage = this.summary().stage;
    if (stage === 'FAILED') return 'text-red-700';
    if (stage === 'CANCELLED') return 'text-slate-500';
    if (stage === 'AWAITING_REVIEW') return 'text-indigo-700';
    if (stage === 'COMPLETED') return 'text-emerald-700';
    return 'text-indigo-600';
  }

  protected statusGlyph(): string {
    const stage = this.summary().stage;
    if (stage === 'COMPLETED') return '✓';
    if (stage === 'FAILED') return '!';
    if (stage === 'CANCELLED') return '×';
    return '';
  }

  protected scoreLabel(): string | null {
    const score = this.summary().score;
    return score === null ? null : `Match score ${score} out of 100`;
  }

  protected startRename(): void {
    this.menuOpen.set(false);
    this.editing.set(true);
  }

  protected onSaved(title: string): void {
    this.editing.set(false);
    if (title !== this.summary().title) this.renamed.emit({ id: this.summary().id, title });
  }

  protected formatDate = relativeTime;
}
