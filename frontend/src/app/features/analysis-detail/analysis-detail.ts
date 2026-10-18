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
import { AnalysisDetail as Detail, AnalysisStage } from '../../core/models';
import { AppliedToggle } from '../../shared/applied-toggle/applied-toggle';
import { InlineTitleEdit } from '../../shared/inline-title-edit/inline-title-edit';
import { KeywordChips } from '../../shared/keyword-chips/keyword-chips';
import { ProgressInline } from '../../shared/progress-inline/progress-inline';
import { RequirementList } from '../../shared/requirement-list/requirement-list';
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
    RequirementList,
    ScoreBadge,
    StepIndicator,
    SuggestionList,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './analysis-detail.html',
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
   * Whether the step-2 action can run: at least one selected suggestion, or none at all when the
   * evaluation proposed no changes (the analyze prompt allows an empty list — docs/API.md §4).
   */
  protected readonly canApply = computed(() => {
    const analysis = this.detail();
    if (!analysis || !this.reviewing()) return false;
    return analysis.suggestions.length === 0 || this.selectedIds().size > 0;
  });

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
    if (analysis.suggestions.length > 0) {
      if (this.selectedIds().size === 0) return;
      this.analyses.apply(analysis.id, [...this.selectedIds()]);
      return;
    }
    // No suggestions: still start the rewrite so the ATS-formatted CV can be generated.
    this.analyses.apply(analysis.id, []);
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
      // Do not jump to the review step when evaluation finishes; the user reads Match first and
      // opens Review changes themselves (the step indicator marks it current and clickable).
      return 0;
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
