import { computed, inject, Injectable, signal } from '@angular/core';
import { WorkspaceApi } from './api/workspace.api';
import { problemMessage } from './error-messages';
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
  private readonly _titleEditId = signal<string | null>(null);

  readonly analyses = this._analyses.asReadonly();
  readonly selectedId = this._selectedId.asReadonly();
  readonly detailLoading = this._detailLoading.asReadonly();
  readonly creating = this._creating.asReadonly();
  readonly actionError = this._actionError.asReadonly();

  /** The id of the analysis that should open in inline title-edit mode, if any. */
  readonly titleEditId = this._titleEditId.asReadonly();

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

  /** Opens an analysis with its title ready to edit (the sidebar's Rename menu). */
  requestTitleEdit(id: string): void {
    this._titleEditId.set(id);
    this.select(id);
  }

  /** Consumes the pending inline-edit request once the detail has reacted to it. */
  consumeTitleEdit(id: string): void {
    if (this._titleEditId() === id) this._titleEditId.set(null);
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

  rename(id: string, title: string): void {
    const trimmed = title.trim();
    if (!trimmed) return;
    this._actionError.set(null);
    this.api.renameAnalysis(id, trimmed).subscribe({
      next: (summary) => this.upsert(summary),
      error: (err: unknown) => this._actionError.set(problemMessage(err, 'The title could not be saved.')),
    });
  }

  apply(id: string, approvedSuggestionIds: string[]): void {
    this._actionError.set(null);
    this.api.applyAnalysis(id, approvedSuggestionIds).subscribe({
      next: (summary) => this.upsert(summary),
      error: (err: unknown) => this._actionError.set(problemMessage(err, 'The changes could not be applied.')),
    });
  }

  retry(id: string): void {
    this._actionError.set(null);
    this.api.retryAnalysis(id).subscribe({
      next: (summary) => {
        this.upsert(summary);
        this.loadDetail(id);
      },
      error: (err: unknown) => this._actionError.set(problemMessage(err, 'The retry could not be started.')),
    });
  }

  remove(id: string): void {
    this.api.deleteAnalysis(id).subscribe({
      next: () => this.dropLocally(id),
      error: (err: unknown) => this._actionError.set(problemMessage(err, 'The analysis could not be deleted.')),
    });
  }

  clearActionError(): void {
    this._actionError.set(null);
  }

  /** Applies an `analysis.updated` event. */
  onAnalysisUpdated(summary: AnalysisSummary): void {
    const previous = this._analyses().find((a) => a.id === summary.id) ?? null;
    this.upsert(summary);

    const detailNeeded =
      summary.id === this._selectedId() && !this._details()[summary.id];
    const stageChanged = previous?.stage !== summary.stage;
    if (summary.stage === 'AWAITING_REVIEW' || detailNeeded || (stageChanged && summary.id === this._selectedId())) {
      this.loadDetail(summary.id);
    }
    if (summary.stage === 'COMPLETED' && previous?.stage !== 'COMPLETED' && summary.id !== this._selectedId()) {
      this.toast.show(`"${summary.title}" is ready`);
    }
  }

  /** Applies an `analysis.deleted` event (also used after a local delete). */
  onAnalysisDeleted(id: string): void {
    this.dropLocally(id);
  }

  refreshDetail(id: string): void {
    this.loadDetail(id);
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
export const ACTIVE_STAGES: readonly AnalysisStage[] = ['QUEUED', 'EVALUATING', 'REWRITING', 'GENERATING_PDF'];

export const STAGE_LABELS: Record<AnalysisStage, string> = {
  QUEUED: 'Queued',
  EVALUATING: 'Checking your CV',
  AWAITING_REVIEW: 'Needs review',
  REWRITING: 'Applying changes',
  GENERATING_PDF: 'Building the PDF',
  COMPLETED: 'Ready',
  FAILED: 'Failed',
};

export function isWorking(stage: AnalysisStage): boolean {
  return ACTIVE_STAGES.includes(stage);
}
