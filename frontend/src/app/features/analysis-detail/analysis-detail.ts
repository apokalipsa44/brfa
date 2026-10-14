import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';

import { AnalysesStore, isWorking } from '../../core/analyses.store';
import { errorMessage } from '../../core/error-messages';
import { formatDateTime } from '../../core/format';
import { AnalysisDetail as Detail, AnalysisStage } from '../../core/models';
import { AppliedToggle } from '../../shared/applied-toggle/applied-toggle';
import { InlineTitleEdit } from '../../shared/inline-title-edit/inline-title-edit';
import { KeywordChips } from '../../shared/keyword-chips/keyword-chips';
import { ProgressInline } from '../../shared/progress-inline/progress-inline';
import { ScoreBadge } from '../../shared/score-badge/score-badge';
import { StepIndicator } from '../../shared/step-indicator/step-indicator';
import { SuggestionList } from '../../shared/suggestion-list/suggestion-list';

/**
 * Analysis detail (docs/API.md §4): inline-editable title, step indicator, and the three stages —
 * progress, the review of approved changes, and the result. Failures map an error code to friendly
 * copy with a Retry that re-runs the failed stage.
 */
@Component({
  selector: 'app-analysis-detail',
  imports: [AppliedToggle, InlineTitleEdit, KeywordChips, ProgressInline, ScoreBadge, StepIndicator, SuggestionList],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (detail(); as analysis) {
      <div class="mx-auto max-w-3xl">
        <!-- Header: title with a visible rename button, status badge, applied marker, created time. -->
        <div class="flex flex-wrap items-start justify-between gap-3">
          <div class="min-w-0 flex-1">
            @if (editing()) {
              <app-inline-title-edit
                [value]="analysis.title"
                size="lg"
                ariaLabel="Rename analysis"
                (saved)="saveTitle(analysis, $event)"
                (cancelled)="cancelEdit()"
              />
            } @else {
              <div class="flex min-w-0 items-center gap-2">
                <h2 class="min-w-0 truncate text-xl font-semibold text-slate-900">{{ analysis.title }}</h2>
                <button
                  type="button"
                  class="inline-flex h-7 w-7 shrink-0 items-center justify-center rounded-md text-slate-400 transition-colors duration-150 hover:bg-slate-100 hover:text-indigo-600 focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:outline-none"
                  aria-label="Rename analysis"
                  title="Rename"
                  (click)="startEdit(analysis)"
                >
                  <span aria-hidden="true">✎</span>
                </button>
              </div>
            }
            <p class="mt-1 text-xs text-slate-500">Created {{ formatDateTime(analysis.createdAt) }}</p>
          </div>

          <div class="flex flex-col items-end gap-2">
            <span
              class="rounded-full px-2.5 py-1 text-xs font-medium"
              [class]="statusBadgeClasses(analysis.stage)"
              aria-live="polite"
              >{{ stageLabel(analysis.stage) }}</span
            >
            <app-applied-toggle
              variant="switch"
              [applied]="analysis.appliedAt !== null"
              [appliedAt]="analysis.appliedAt"
              (toggled)="setApplied(analysis, $event)"
              (datePicked)="setApplied(analysis, $event)"
            />
          </div>
        </div>

        <div class="mt-4">
          <app-step-indicator
            [stage]="analysis.stage"
            [failedStage]="analysis.stage === 'FAILED' ? (analysis.score === null ? 'EVALUATING' : 'REWRITING') : null"
          />
        </div>

        <!-- Working: calm inline progress. -->
        @if (working()) {
          <div class="mt-5">
            <app-progress-inline [stage]="analysis.stage" [progress]="analysis.progress" />
          </div>
        }

        <!-- Awaiting review: score, summary, keywords, suggested changes. -->
        @if (analysis.stage === 'AWAITING_REVIEW') {
          <section class="mt-6">
            <div class="flex items-center gap-4 rounded-xl border border-slate-200 bg-white p-4">
              <app-score-badge [score]="analysis.score" size="lg" />
              <div class="min-w-0">
                <h3 class="text-sm font-semibold text-slate-900">Match score</h3>
                @if (analysis.summary) {
                  <p class="mt-1 text-sm text-slate-600">{{ analysis.summary }}</p>
                }
              </div>
            </div>

            <div class="mt-4 flex flex-col gap-3">
              <app-keyword-chips
                title="Matched keywords"
                tone="matched"
                [keywords]="analysis.matchedKeywords"
              />
              <app-keyword-chips
                title="Not in your CV"
                tone="missing"
                [keywords]="analysis.missingKeywords"
              />
            </div>

            <div class="mt-5 flex flex-wrap items-center justify-between gap-2">
              <h3 class="text-sm font-semibold text-slate-900">Suggested changes</h3>
              <div class="flex gap-2 text-xs">
                <button
                  type="button"
                  class="rounded-md px-2 py-1 font-medium text-indigo-600 hover:bg-indigo-50"
                  (click)="selectAll(analysis)"
                >
                  Select all
                </button>
                <button
                  type="button"
                  class="rounded-md px-2 py-1 font-medium text-slate-500 hover:bg-slate-100"
                  (click)="selectNone()"
                >
                  Select none
                </button>
              </div>
            </div>

            <div class="mt-2 flex flex-col gap-2">
              <app-suggestion-list
                [suggestions]="analysis.suggestions"
                [selectedIds]="selectedIds()"
                (toggled)="toggleSuggestion($event)"
              />
            </div>

            @if (analyses.actionError(); as message) {
              <p role="alert" class="mt-3 rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700">{{ message }}</p>
            }

            <button
              type="button"
              class="mt-4 rounded-lg px-4 py-2 text-sm font-medium transition-colors duration-200 focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-2 focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-50"
              [class]="selectedCount() > 0 ? 'bg-indigo-600 text-white hover:bg-indigo-700' : 'border border-slate-300 text-slate-400'"
              [disabled]="selectedCount() === 0"
              (click)="apply(analysis)"
            >
              Apply selected changes
              <span class="font-normal">· {{ selectedCount() }} of {{ analysis.suggestions.length }} selected</span>
            </button>
          </section>
        }

        <!-- Completed: download and applied changes. -->
        @if (analysis.stage === 'COMPLETED') {
          <section class="mt-6">
            @if (analysis.resultUrl) {
              <a
                [href]="analysis.resultUrl"
                download
                class="inline-flex items-center rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white transition-colors duration-200 hover:bg-indigo-700 focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-2 focus-visible:outline-none"
              >
                Download improved CV
              </a>
            } @else {
              <p class="rounded-lg border border-slate-200 bg-white px-4 py-3 text-sm text-slate-500">
                The improved CV is ready, but the file is no longer available.
              </p>
            }

            @if (analysis.appliedChanges && analysis.appliedChanges.length > 0) {
              <h3 class="mt-6 text-sm font-semibold text-slate-900">Changes applied</h3>
              <ul class="mt-2 flex flex-col gap-2">
                @for (change of analysis.appliedChanges; track $index) {
                  <li class="rounded-xl border border-slate-200 bg-white p-3">
                    <p class="text-sm font-medium text-slate-900">{{ change.section }}</p>
                    @if (change.before) {
                      <p class="mt-1 text-sm text-slate-500 line-through">{{ change.before }}</p>
                    }
                    <p class="text-sm text-slate-700">{{ change.after }}</p>
                    @if (change.reason) {
                      <p class="mt-1 text-xs text-slate-500">{{ change.reason }}</p>
                    }
                  </li>
                }
              </ul>
            }
          </section>
        }

        <!-- Failed: friendly copy, retry, technical details collapsed. -->
        @if (analysis.stage === 'FAILED') {
          <section class="mt-6">
            <div class="rounded-xl border border-red-200 bg-red-50 p-4" role="alert">
              <p class="text-sm font-medium text-red-800">The analysis did not finish</p>
              <p class="mt-1 text-sm text-red-700">
                {{
                  errorMessage(analysis.errorCode, 'The analysis could not complete. Try running it again.')
                }}
              </p>
              <button
                type="button"
                class="mt-3 rounded-lg bg-red-600 px-3 py-1.5 text-sm font-medium text-white transition-colors duration-200 hover:bg-red-700 focus-visible:ring-2 focus-visible:ring-red-500 focus-visible:ring-offset-2 focus-visible:outline-none"
                (click)="retry(analysis)"
              >
                Retry
              </button>
            </div>

            @if (analysis.errorCode || analysis.errorMessage) {
              <details class="mt-3">
                <summary class="cursor-pointer text-xs text-slate-500 hover:text-slate-700">
                  Technical details
                </summary>
                <p class="mt-2 rounded-lg border border-slate-200 bg-white p-3 text-xs text-slate-600">
                  @if (analysis.errorCode) {
                    <span class="font-mono">{{ analysis.errorCode }}</span>
                  }
                  @if (analysis.errorMessage) {
                    <span class="mt-1 block">{{ analysis.errorMessage }}</span>
                  }
                </p>
              </details>
            }
          </section>
        }
      </div>
    } @else {
      <div class="flex h-full items-center justify-center" aria-live="polite">
        @if (analyses.detailLoading()) {
          <p class="text-sm text-slate-500">Loading…</p>
        } @else {
          <p class="text-sm text-slate-500">This analysis is no longer available.</p>
        }
      </div>
    }
  `,
})
export class AnalysisDetail {
  protected readonly analyses = inject(AnalysesStore);

  readonly detail = computed<Detail | null>(() => this.analyses.selected());
  protected readonly working = computed(() => {
    const stage = this.detail()?.stage;
    return !!stage && isWorking(stage);
  });

  protected readonly editing = signal(false);
  protected readonly selection = signal<ReadonlySet<string>>(new Set());

  private readonly editingId = signal<string | null>(null);
  private readonly lastInitKey = signal('');

  readonly selectedIds = computed(() => this.selection());
  readonly selectedCount = computed(() => this.selection().size);

  constructor() {
    // Defaults: high and medium priority changes start checked (docs/API.md UI).
    effect(() => {
      const analysis = this.detail();
      if (!analysis || analysis.suggestions.length === 0) return;
      const key = `${analysis.id}:${analysis.suggestions.length}`;
      if (key === this.lastInitKey()) return;
      this.lastInitKey.set(key);
      this.selection.set(
        new Set(analysis.suggestions.filter((s) => s.priority !== 'LOW').map((s) => s.id)),
      );
    });
  }

  startEdit(analysis: Detail): void {
    if (this.editing()) return;
    this.editing.set(true);
    // Keep the reference so a stale closure cannot rename a different analysis.
    this.editingId.set(analysis.id);
  }

  saveTitle(analysis: Detail, title: string): void {
    this.editing.set(false);
    if (this.editingId() !== analysis.id) return;
    this.editingId.set(null);
    if (title && title !== analysis.title) this.analyses.rename(analysis.id, title);
  }

  cancelEdit(): void {
    this.editing.set(false);
    this.editingId.set(null);
  }

  /** Requested applied state, or an explicit ISO date when the user picked one. */
  setApplied(analysis: Detail, applied: boolean | string): void {
    this.analyses.setApplied(analysis.id, applied);
  }

  toggleSuggestion(id: string): void {
    this.selection.update((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  selectAll(analysis: Detail): void {
    this.selection.set(new Set(analysis.suggestions.map((s) => s.id)));
  }

  selectNone(): void {
    this.selection.set(new Set());
  }

  apply(analysis: Detail): void {
    if (this.selection().size === 0) return;
    this.analyses.apply(analysis.id, [...this.selection()]);
  }

  retry(analysis: Detail): void {
    this.selection.set(new Set());
    this.lastInitKey.set('');
    this.analyses.retry(analysis.id);
  }

  protected readonly stageLabel = (stage: Detail['stage']): string =>
    SHORT_STAGE_LABELS[stage] ?? stage;

  protected statusBadgeClasses(stage: Detail['stage']): string {
    if (stage === 'COMPLETED') return 'bg-emerald-50 text-emerald-700';
    if (stage === 'FAILED') return 'bg-red-50 text-red-700';
    if (stage === 'AWAITING_REVIEW') return 'bg-indigo-50 text-indigo-700';
    return 'bg-slate-100 text-slate-600';
  }

  protected formatDateTime = formatDateTime;
  protected errorMessage = errorMessage;
}

/** Short labels for the detail header badge. */
const SHORT_STAGE_LABELS: Record<AnalysisStage, string> = {
  QUEUED: 'Queued',
  EVALUATING: 'Checking',
  AWAITING_REVIEW: 'Needs review',
  REWRITING: 'Applying changes',
  GENERATING_PDF: 'Building PDF',
  COMPLETED: 'Ready',
  FAILED: 'Failed',
  CANCELLED: 'Cancelled',
};
