import { computed, inject, Injectable, signal } from '@angular/core';
import { AnalysisPatch, WorkspaceApi } from './api/workspace.api';
import { problemMessage } from './error-messages';
import { todayIso } from './format';
import { AnalysisDetail, AnalysisStage, AnalysisSummary } from './models';
import { ToastStore } from './toast.store';

/**
 * Analyses list + selection (docs/API.md §4). The backend is the source of truth: the list is loaded
 * over REST on start and updated live from `analysis.updated` / `analysis.deleted` events. Detail
 * payloads (suggestions, keywords, applied changes) are fetched lazily when an analysis becomes
 * relevant, so the summary stream stays small.
 */
@Injectable({ providedIn: 'root' })
export class AnalysesStore {
  private readonly api = inject(WorkspaceApi);
  private readonly toast = inject(ToastStore);

  private readonly _analyses = signal<AnalysisSummary[]>([]);
  private readonly _selectedId = signal<string | null>(null);
  private readonly _details = signal<Record<string, AnalysisDetail>>({});
  private readonly _detailLoading = signal(false);
  private readonly _creating = signal(false);
  private readonly _actionError = signal<string | null>(null);

  readonly analyses = this._analyses.asReadonly();
  readonly selectedId = this._selectedId.asReadonly();
  readonly detailLoading = this._detailLoading.asReadonly();
  readonly creating = this._creating.asReadonly();
  readonly actionError = this._actionError.asReadonly();

  /** The detail of the selected analysis, or null while it is not loaded/selected. */
  readonly selected = computed<AnalysisDetail | null>(() => {
    const id = this._selectedId();
    return id ? (this._details()[id] ?? null) : null;
  });

  /** The summary of the selected analysis (available even before its detail arrives). */
  readonly selectedSummary = computed<AnalysisSummary | null>(() => {
    const id = this._selectedId();
    return id ? (this._analyses().find((a) => a.id === id) ?? null) : null;
  });

  constructor() {
    this.load();
  }

  private load(): void {
    this.api.listAnalyses().subscribe({
      next: (list) => this._analyses.set([...list].sort(byNewest)),
      error: () => this._analyses.set([]),
    });
  }

  select(id: string | null): void {
    this._selectedId.set(id);
    if (id) this.loadDetail(id);
  }

  create(jobPosting: string): void {
    this._actionError.set(null);
    this._creating.set(true);
    this.api.createAnalysis(jobPosting).subscribe({
      next: (summary) => {
        this.upsert(summary);
        this._creating.set(false);
        this.select(summary.id);
      },
      error: (err: unknown) => {
        this._creating.set(false);
        this._actionError.set(problemMessage(err, 'The analysis could not be started.'));
      },
    });
  }

  /** Renames the analysis; the new title shows immediately and rolls back if the save fails. */
  rename(id: string, title: string): void {
    const trimmed = title.trim();
    if (!trimmed) return;
    this.patch(id, { title: trimmed }, { title: trimmed });
  }

  /**
   * Sets the user description (docs/API.md §4); a blank value clears the override, so the backend
   * falls back to the evaluation's description. The change shows immediately and rolls back on error.
   */
  updateDescription(id: string, description: string): void {
    const trimmed = description.trim();
    const fields: Partial<AnalysisSummary> = trimmed ? { shortDescription: trimmed } : {};
    this.patch(id, { description: trimmed }, fields);
  }

  /**
   * Marks or clears the "applied to job" marker (docs/API.md §4). `true` uses today's date (keeping an
   * existing one), `false` clears it, and an ISO date string sets an explicit date.
   */
  setApplied(id: string, applied: boolean | string): void {
    if (typeof applied === 'string') {
      this.patch(id, { appliedAt: applied }, { appliedAt: applied });
      return;
    }
    const current = this.entryFor(id);
    const appliedAt = applied ? (current?.appliedAt ?? todayIso()) : null;
    this.patch(id, { applied }, { appliedAt });
  }

  /** Optimistically applies `fields`, then confirms or rolls back from the PATCH response. */
  private patch(id: string, body: AnalysisPatch, fields: Partial<AnalysisSummary>): void {
    const previous: Partial<AnalysisSummary> = {
      title: this.entryFor(id)?.title,
      shortDescription: this.entryFor(id)?.shortDescription ?? null,
      appliedAt: this.entryFor(id)?.appliedAt ?? null,
    };
    this._actionError.set(null);
    this.applyLocal(id, fields);
    this.api.updateAnalysis(id, body).subscribe({
      next: (summary) => this.upsert(summary),
      error: (err: unknown) => {
        this.applyLocal(id, previous);
        const message = problemMessage(err, 'The change could not be saved.');
        this._actionError.set(message);
        this.toast.show(message);
      },
    });
  }

  /** The list entry for `id`, or the loaded detail as a fallback (both carry title + appliedAt). */
  private entryFor(id: string): AnalysisSummary | AnalysisDetail | undefined {
    return this._analyses().find((a) => a.id === id) ?? this._details()[id];
  }

  apply(id: string, approvedSuggestionIds: string[]): void {
    this._actionError.set(null);
    this.api.applyAnalysis(id, approvedSuggestionIds).subscribe({
      next: (summary) => this.upsert(summary),
      error: (err: unknown) =>
        this._actionError.set(problemMessage(err, 'The changes could not be applied.')),
    });
  }

  retry(id: string): void {
    this._actionError.set(null);
    this.api.retryAnalysis(id).subscribe({
      next: (summary) => {
        this.upsert(summary);
        this.loadDetail(id);
      },
      error: (err: unknown) =>
        this._actionError.set(problemMessage(err, 'The retry could not be started.')),
    });
  }

  remove(id: string): void {
    this.api.deleteAnalysis(id).subscribe({
      next: () => this.dropLocally(id),
      error: (err: unknown) =>
        this._actionError.set(problemMessage(err, 'The analysis could not be deleted.')),
    });
  }

  /** Applies an `analysis.updated` event. */
  onAnalysisUpdated(summary: AnalysisSummary): void {
    const previous = this._analyses().find((a) => a.id === summary.id) ?? null;
    this.upsert(summary);

    const detailNeeded = summary.id === this._selectedId() && !this._details()[summary.id];
    const stageChanged = previous?.stage !== summary.stage;
    if (
      summary.stage === 'AWAITING_REVIEW' ||
      detailNeeded ||
      (stageChanged && summary.id === this._selectedId())
    ) {
      this.loadDetail(summary.id);
    }
    if (
      summary.stage === 'COMPLETED' &&
      previous?.stage !== 'COMPLETED' &&
      summary.id !== this._selectedId()
    ) {
      this.toast.show(`"${summary.title}" is ready`);
    }
  }

  /** Applies an `analysis.deleted` event (also used after a local delete). */
  onAnalysisDeleted(id: string): void {
    this.dropLocally(id);
  }

  private loadDetail(id: string): void {
    this._detailLoading.set(true);
    this.api.getAnalysis(id).subscribe({
      next: (detail) => {
        this._details.update((map) => ({ ...map, [id]: detail }));
        this._detailLoading.set(false);
      },
      error: () => {
        // A 404 means the backend no longer knows this id (restart): drop it silently.
        this._detailLoading.set(false);
        this.onAnalysisDeleted(id);
      },
    });
  }

  private upsert(summary: AnalysisSummary): void {
    this._analyses.update((list) => {
      const next = list.filter((a) => a.id !== summary.id);
      next.push(summary);
      return next.sort(byNewest);
    });
    // Keep the open detail (and its header) in step with the summary returned by the backend.
    this.applyLocal(summary.id, summary);
  }

  /** Merges `fields` into the list entry and, when loaded, the detail entry for `id`. */
  private applyLocal(id: string, fields: Partial<AnalysisSummary>): void {
    this._analyses.update((list) =>
      list.map((a) => (a.id === id ? { ...a, ...fields } : a)).sort(byNewest),
    );
    this._details.update((map) => (map[id] ? { ...map, [id]: { ...map[id], ...fields } } : map));
  }

  private dropLocally(id: string): void {
    this._analyses.update((list) => list.filter((a) => a.id !== id));
    this._details.update((map) => {
      const next = { ...map };
      delete next[id];
      return next;
    });
    if (this._selectedId() === id) this._selectedId.set(null);
  }
}

function byNewest(a: AnalysisSummary, b: AnalysisSummary): number {
  return new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime();
}

/** Stage helpers shared by the UI. */
const ACTIVE_STAGES: readonly AnalysisStage[] = [
  'QUEUED',
  'FETCHING_POSTING',
  'EVALUATING',
  'REWRITING',
  'GENERATING_PDF',
];

export const STAGE_LABELS: Record<AnalysisStage, string> = {
  QUEUED: 'Queued',
  FETCHING_POSTING: 'Fetching the posting',
  EVALUATING: 'Checking your CV',
  AWAITING_REVIEW: 'Needs review',
  REWRITING: 'Applying changes',
  GENERATING_PDF: 'Building the PDF',
  COMPLETED: 'Ready',
  FAILED: 'Failed',
  CANCELLED: 'Cancelled',
};

export function isWorking(stage: AnalysisStage): boolean {
  return ACTIVE_STAGES.includes(stage);
}
