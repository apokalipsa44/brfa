import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';

import { AnalysesStore, isWorking } from '../../core/analyses.store';
import { errorMessage } from '../../core/error-messages';
import { formatDateTime } from '../../core/format';
import {
  AnalysisDetail as Detail,
  AnalysisStage,
  RequirementStatus,
  RequirementType,
} from '../../core/models';
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
 * copy with a Retry that re-runs the failed stage; a working analysis can be cancelled in place.
 */
@Component({
  selector: 'app-analysis-detail',
  imports: [
    AppliedToggle,
    InlineTitleEdit,
    KeywordChips,
    ProgressInline,
    ScoreBadge,
    StepIndicator,
    SuggestionList,
  ],
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
                <h2 class="min-w-0 truncate text-xl font-semibold text-slate-900">
                  {{ analysis.title }}
                </h2>
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
            <p class="mt-1 text-xs text-slate-500">
              Created {{ formatDateTime(analysis.createdAt) }}
            </p>
          </div>

          <div class="flex flex-col items-end gap-2">
            <span
              class="rounded-full px-2.5 py-1 text-xs font-medium"
              [class]="statusBadgeClasses()"
              aria-live="polite"
              >{{ stageLabel() }}</span
            >
            <app-applied-toggle
              variant="switch"
              [applied]="!!analysis.appliedAt"
              [appliedAt]="analysis.appliedAt"
              (toggled)="setApplied(analysis, $event)"
              (datePicked)="setApplied(analysis, $event)"
            />
          </div>
        </div>

        <!-- Role summary: inline-editable description (docs/API.md §4). -->
        <div class="mt-3">
          @if (editingDescription() && editingDescriptionId() === analysis.id) {
            <textarea
              #descriptionField
              rows="2"
              maxlength="200"
              class="w-full resize-none rounded-lg border border-indigo-500 bg-white px-3 py-2 text-sm text-slate-700 focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:outline-none"
              aria-label="Edit role description"
              [value]="analysis.shortDescription ?? ''"
              (keydown.escape)="cancelDescriptionEdit()"
            ></textarea>
            <div class="mt-1 flex gap-2 text-xs">
              <button
                type="button"
                class="rounded-md bg-indigo-600 px-2 py-1 font-medium text-white hover:bg-indigo-700"
                (click)="saveDescription(analysis, descriptionField)"
              >
                Save
              </button>
              <button
                type="button"
                class="rounded-md px-2 py-1 font-medium text-slate-500 hover:bg-slate-100"
                (click)="cancelDescriptionEdit()"
              >
                Cancel
              </button>
            </div>
          } @else {
            <div class="flex items-start gap-2">
              @if (analysis.shortDescription) {
                <p class="min-w-0 flex-1 text-sm text-slate-600">
                  {{ analysis.shortDescription }}
                </p>
              } @else {
                <p class="min-w-0 flex-1 text-sm text-slate-400 italic">No description yet.</p>
              }
              <button
                type="button"
                class="inline-flex h-6 w-6 shrink-0 items-center justify-center rounded-md text-slate-400 transition-colors duration-150 hover:bg-slate-100 hover:text-indigo-600 focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:outline-none"
                aria-label="Edit role description"
                title="Edit description"
                (click)="startDescriptionEdit(analysis)"
              >
                <span aria-hidden="true">✎</span>
              </button>
            </div>
          }
        </div>

        <!-- Source link: the posting the analysis used, with a full re-run (docs/API.md §4). -->
        @if (analysis.sourceUrl; as url) {
          <div class="mt-2 flex flex-wrap items-center gap-2">
            <a
              [href]="url"
              target="_blank"
              rel="noopener noreferrer"
              aria-label="Open the job posting link in a new tab"
              class="inline-flex min-w-0 items-center gap-1 text-sm text-indigo-600 transition-colors duration-150 hover:text-indigo-700 hover:underline focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:outline-none"
            >
              <span aria-hidden="true">↗</span>
              <span class="truncate">{{ url }}</span>
            </a>
            <button
              type="button"
              class="rounded-md border border-slate-300 bg-white px-2.5 py-1 text-xs font-medium text-slate-700 transition-colors duration-150 hover:border-indigo-300 hover:bg-indigo-50 hover:text-indigo-700 focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-50"
              [disabled]="working()"
              title="Fetch the posting again and re-run the whole analysis"
              (click)="checkAgain(analysis)"
            >
              Check again
            </button>
          </div>
        }

        <div class="mt-4">
          <app-step-indicator
            [stage]="analysis.stage"
            [failedStage]="failedStage()"
            [viewed]="viewedStep()"
            [available]="availableSteps()"
            (stepSelected)="onStepSelected($event)"
          />
        </div>

        <!-- Working: calm inline progress. -->
        @if (working()) {
          <div class="mt-5">
            <app-progress-inline
              [stage]="analysis.stage"
              [progress]="analysis.progress"
              [message]="analysis.stageMessage ?? null"
            />
            @if (liveOutput(analysis.id); as live) {
              <pre
                class="mt-3 max-h-40 overflow-auto rounded-lg border border-slate-200 bg-slate-50 p-3 text-xs leading-relaxed break-words whitespace-pre-wrap text-slate-500"
              >{{ live }}</pre>
            }
            <button
              type="button"
              class="mt-3 inline-flex items-center rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-sm font-medium text-slate-700 transition-colors duration-200 hover:border-red-300 hover:bg-red-50 hover:text-red-700 focus-visible:ring-2 focus-visible:ring-red-500 focus-visible:ring-offset-2 focus-visible:outline-none"
              (click)="cancel(analysis)"
            >
              Cancel analysis
            </button>
          </div>
        }

        <!-- Step 1 — Match: score, summary and keyword coverage. -->
        @if (viewedStep() === 0 && analysis.score !== null) {
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

            @if (requirements().length > 0) {
              <div class="mt-4">
                <h3 class="text-sm font-semibold text-slate-900">Requirements from the posting</h3>
                <ul class="mt-2 flex flex-col gap-1.5">
                  @for (requirement of requirements(); track $index) {
                    <li
                      class="flex items-start gap-2 rounded-lg border border-slate-200 bg-white p-2.5"
                    >
                      <span
                        class="mt-0.5 shrink-0 rounded-full border px-2 py-0.5 text-xs font-medium"
                        [class]="requirementStatusTone(requirement.status)"
                      >
                        {{ REQUIREMENT_STATUS_LABEL[requirement.status] }}
                      </span>
                      <span class="min-w-0 flex-1">
                        <span class="block text-sm text-slate-700">{{ requirement.text }}</span>
                        @if (requirement.cvEvidence) {
                          <span class="mt-0.5 block text-xs text-slate-500"
                            >CV: “{{ requirement.cvEvidence }}”</span
                          >
                        }
                      </span>
                      <span class="mt-0.5 shrink-0 text-xs text-slate-400">{{
                        REQUIREMENT_TYPE_LABEL[requirement.type]
                      }}</span>
                    </li>
                  }
                </ul>
              </div>
            }
          </section>
        }

        <!-- Step 2 — Review changes: suggestions; approval is only possible while AWAITING_REVIEW. -->
        @if (viewedStep() === 1 && analysis.suggestions.length > 0) {
          <section class="mt-6">
            <div class="flex flex-wrap items-center justify-between gap-2">
              <h3 class="text-sm font-semibold text-slate-900">Suggested changes</h3>
              @if (reviewing()) {
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
              }
            </div>

            <div class="mt-2 flex flex-col gap-2">
              <app-suggestion-list
                [suggestions]="analysis.suggestions"
                [selectedIds]="selectedIds()"
                (toggled)="toggleSuggestion($event)"
              />
            </div>

            @if (reviewing()) {
              @if (analyses.actionError(); as message) {
                <p role="alert" class="mt-3 rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700">
                  {{ message }}
                </p>
              }

              <button
                type="button"
                class="mt-4 rounded-lg px-4 py-2 text-sm font-medium transition-colors duration-200 focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-2 focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-50"
                [class]="
                  selectedCount() > 0
                    ? 'bg-indigo-600 text-white hover:bg-indigo-700'
                    : 'border border-slate-300 text-slate-400'
                "
                [disabled]="selectedCount() === 0"
                (click)="apply(analysis)"
              >
                Apply selected changes
                <span class="font-normal"
                  >· {{ selectedCount() }} of {{ analysis.suggestions.length }} selected</span
                >
              </button>
            }
          </section>
        }

        <!-- Step 3 — Improved CV: download and the changes that were applied. -->
        @if (viewedStep() === 2) {
          <section class="mt-6">
            @if (analysis.resultUrl) {
              <a
                [href]="analysis.resultUrl"
                download
                class="inline-flex items-center rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white transition-colors duration-200 hover:bg-indigo-700 focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-2 focus-visible:outline-none"
              >
                Download improved CV
              </a>
            } @else if (analysis.stage === 'COMPLETED') {
              <p
                class="rounded-lg border border-slate-200 bg-white px-4 py-3 text-sm text-slate-500"
              >
                The improved CV is ready, but the file is no longer available.
              </p>
            }

            @if (analysis.appliedChanges && analysis.appliedChanges.length > 0) {
              <h3 class="mt-6 text-sm font-semibold text-slate-900">Changes applied</h3>
              <ul class="mt-2 flex flex-col gap-2">
                @for (change of analysis.appliedChanges; track change.section + ':' + $index) {
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
                  errorMessage(
                    analysis.errorCode,
                    'The analysis could not complete. Try running it again.'
                  )
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
                <p
                  class="mt-2 rounded-lg border border-slate-200 bg-white p-3 text-xs text-slate-600"
                >
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

  /** True while suggestions can still be approved (docs/API.md §4). */
  protected readonly reviewing = computed(() => this.detail()?.stage === 'AWAITING_REVIEW');

  /**
   * Number of leading steps with a persisted result: 0 before evaluation, 2 after it, 3 once a
   * result exists (completed or rewritten). Every result lives in the analysis snapshot, so a step
   * can be re-opened at any later stage; the indicator only makes available ones clickable.
   */
  protected readonly availableSteps = computed(() => {
    const analysis = this.detail();
    if (!analysis) return 0;
    if (
      analysis.stage === 'COMPLETED' ||
      analysis.resultUrl !== null ||
      (analysis.appliedChanges?.length ?? 0) > 0
    ) {
      return 3;
    }
    return analysis.score !== null || analysis.suggestions.length > 0 ? 2 : 0;
  });

  protected readonly editing = signal(false);
  protected readonly editingDescription = signal(false);
  readonly selectedIds = signal<ReadonlySet<string>>(new Set());

  /** Step currently shown (0 match, 1 review, 2 improved CV); the user can re-open finished steps. */
  protected readonly viewedStep = signal(0);

  private readonly editingId = signal<string | null>(null);
  private readonly editingDescriptionId = signal<string | null>(null);
  private readonly lastInitKey = signal('');
  private readonly lastViewKey = signal('');

  protected readonly selectedCount = computed(() => this.selectedIds().size);

  /** The posting requirements of the selected analysis (docs/API.md §4). */
  protected readonly requirements = computed(() => this.detail()?.requirements ?? []);

  /** Live streamed LLM output for an analysis (docs/API.md §7), or an empty string when idle. */
  protected liveOutput(id: string): string {
    return this.analyses.liveOutput()[id] ?? '';
  }

  /** The stage a FAILED analysis stopped in (docs/API.md §5), for the step indicator. */
  protected readonly failedStage = computed<AnalysisStage | null>(() => {
    const analysis = this.detail();
    if (!analysis || analysis.stage !== 'FAILED') return null;
    if (analysis.errorCode && FETCH_STAGE_ERROR_CODES.has(analysis.errorCode))
      return 'FETCHING_POSTING';
    return analysis.score === null ? 'EVALUATING' : 'REWRITING';
  });

  protected readonly stageLabel = computed<string>(() => {
    const stage = this.detail()?.stage;
    return stage ? (SHORT_STAGE_LABELS[stage] ?? stage) : '';
  });

  protected readonly statusBadgeClasses = computed<string>(() => {
    const stage = this.detail()?.stage;
    if (stage === 'COMPLETED') return 'bg-emerald-50 text-emerald-700';
    if (stage === 'FAILED') return 'bg-red-50 text-red-700';
    if (stage === 'AWAITING_REVIEW') return 'bg-indigo-50 text-indigo-700';
    return 'bg-slate-100 text-slate-600';
  });

  constructor() {
    // Defaults: high and medium priority changes start checked (docs/API.md UI).
    effect(() => {
      const analysis = this.detail();
      if (!analysis || analysis.suggestions.length === 0) return;
      const key = `${analysis.id}:${analysis.suggestions.length}`;
      if (key === this.lastInitKey()) return;
      this.lastInitKey.set(key);
      this.selectedIds.set(
        new Set(
          analysis.suggestions
            .filter((s) => s.priority !== 'LOW' && !s.needsConfirmation)
            .map((s) => s.id),
        ),
      );
    });

    // Follow the pipeline: on a new stage, snap the viewed step back to that stage's step (clamped
    // to the results that exist), unless the user has since picked a step for the same stage.
    effect(() => {
      const analysis = this.detail();
      if (!analysis) return;
      const key = `${analysis.id}:${analysis.stage}`;
      if (key === this.lastViewKey()) return;
      this.lastViewKey.set(key);
      const available = this.availableSteps();
      if (available === 0) {
        this.viewedStep.set(0);
        return;
      }
      this.viewedStep.set(Math.min(defaultStep(analysis.stage), available - 1));
    });
  }

  /** Opens a step whose result is available (the indicator only emits for those). */
  protected onStepSelected(index: number): void {
    this.viewedStep.set(index);
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

  startDescriptionEdit(analysis: Detail): void {
    if (this.editingDescription()) return;
    this.editingDescription.set(true);
    // Keep the reference so a stale closure cannot edit a different analysis.
    this.editingDescriptionId.set(analysis.id);
  }

  saveDescription(analysis: Detail, field: HTMLTextAreaElement): void {
    const value = field.value.trim();
    this.editingDescription.set(false);
    if (this.editingDescriptionId() !== analysis.id) return;
    this.editingDescriptionId.set(null);
    if (value !== (analysis.shortDescription ?? '').trim()) {
      this.analyses.updateDescription(analysis.id, value);
    }
  }

  cancelDescriptionEdit(): void {
    this.editingDescription.set(false);
    this.editingDescriptionId.set(null);
  }

  /** Requested applied state, or an explicit ISO date when the user picked one. */
  setApplied(analysis: Detail, applied: boolean | string): void {
    this.analyses.setApplied(analysis.id, applied);
  }

  toggleSuggestion(id: string): void {
    if (!this.reviewing()) return;
    this.selectedIds.update((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  selectAll(analysis: Detail): void {
    this.selectedIds.set(new Set(analysis.suggestions.map((s) => s.id)));
  }

  selectNone(): void {
    this.selectedIds.set(new Set());
  }

  apply(analysis: Detail): void {
    if (this.selectedIds().size === 0) return;
    this.analyses.apply(analysis.id, [...this.selectedIds()]);
  }

  retry(analysis: Detail): void {
    this.selectedIds.set(new Set());
    this.lastInitKey.set('');
    this.analyses.retry(analysis.id);
  }

  /** Re-runs a link analysis from scratch, overwriting its previous results (docs/API.md §4). */
  checkAgain(analysis: Detail): void {
    if (this.working()) return;
    this.selectedIds.set(new Set());
    this.lastInitKey.set('');
    this.analyses.rerun(analysis.id);
  }

  /** Stops the running analysis from the detail view (docs/API.md §4). */
  cancel(analysis: Detail): void {
    this.analyses.cancel(analysis.id);
  }

  protected readonly formatDateTime = formatDateTime;
  protected readonly errorMessage = errorMessage;

  protected readonly REQUIREMENT_STATUS_LABEL = REQUIREMENT_STATUS_LABEL;
  protected readonly REQUIREMENT_TYPE_LABEL = REQUIREMENT_TYPE_LABEL;

  protected requirementStatusTone(status: RequirementStatus): string {
    return REQUIREMENT_STATUS_TONE[status];
  }
}

/** Error codes whose failure happened in the FETCHING_POSTING stage (docs/API.md §5). */
const FETCH_STAGE_ERROR_CODES = new Set([
  'URL_INVALID',
  'URL_BLOCKED',
  'FETCH_FAILED',
  'FETCH_NOT_HTML',
  'POSTING_NOT_FOUND',
]);

/** The step a stage maps to: 0 match, 1 review, 2 improved CV. */
function defaultStep(stage: AnalysisStage): number {
  switch (stage) {
    case 'AWAITING_REVIEW':
      return 1;
    case 'REWRITING':
    case 'GENERATING_PDF':
    case 'COMPLETED':
      return 2;
    default:
      return 0;
  }
}

/** Short labels for the detail header badge. */
const SHORT_STAGE_LABELS: Record<AnalysisStage, string> = {
  QUEUED: 'Queued',
  FETCHING_POSTING: 'Fetching posting',
  EVALUATING: 'Checking',
  AWAITING_REVIEW: 'Needs review',
  REWRITING: 'Applying changes',
  GENERATING_PDF: 'Building PDF',
  COMPLETED: 'Ready',
  FAILED: 'Failed',
  CANCELLED: 'Cancelled',
};

/** Requirement coverage badges (docs/API.md §4). */
const REQUIREMENT_STATUS_LABEL: Record<RequirementStatus, string> = {
  MET: 'Met',
  PARTIAL: 'Partial',
  MISSING: 'Missing',
};

const REQUIREMENT_STATUS_TONE: Record<RequirementStatus, string> = {
  MET: 'border-emerald-200 bg-emerald-50 text-emerald-700',
  PARTIAL: 'border-amber-200 bg-amber-50 text-amber-700',
  MISSING: 'border-red-200 bg-red-50 text-red-700',
};

const REQUIREMENT_TYPE_LABEL: Record<RequirementType, string> = {
  MUST: 'Must-have',
  NICE: 'Nice to have',
  SOFT: 'Soft',
};
