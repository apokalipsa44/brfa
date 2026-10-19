import { computed, inject, Injectable, signal } from '@angular/core';

import { GapPatch, WorkspaceApi } from './api/workspace.api';
import { problemMessage } from './error-messages';
import { GapItem, GapKind, GroupedSkill } from './models';
import { ToastStore } from './toast.store';

/**
 * Skill gaps (docs/API.md §9): the missing requirements / nice-to-haves aggregated from every
 * analysis's evaluation, plus the user's curated entries. The backend re-syncs the aggregation on
 * every read, so after each mutation the list is reloaded to refresh the live occurrence counts.
 */
@Injectable({ providedIn: 'root' })
export class GapsStore {
  private readonly api = inject(WorkspaceApi);
  private readonly toast = inject(ToastStore);

  private readonly _items = signal<GapItem[]>([]);
  private readonly _loading = signal(false);
  private readonly _saving = signal(false);
  private readonly _error = signal<string | null>(null);

  private readonly _clusters = signal<GroupedSkill[]>([]);
  private readonly _clustersLoading = signal(false);
  private readonly _rebuilding = signal(false);
  private readonly _clustersError = signal<string | null>(null);
  private readonly _lastRunAt = signal<string | null>(null);

  readonly items = this._items.asReadonly();
  readonly loading = this._loading.asReadonly();
  readonly saving = this._saving.asReadonly();
  readonly error = this._error.asReadonly();

  readonly clusters = this._clusters.asReadonly();
  readonly clustersLoading = this._clustersLoading.asReadonly();
  readonly rebuilding = this._rebuilding.asReadonly();
  readonly clustersError = this._clustersError.asReadonly();
  readonly lastRunAt = this._lastRunAt.asReadonly();

  /** Number of gaps per filter, for the filter tabs. */
  readonly counts = computed(() => {
    const items = this._items();
    return {
      ALL: items.length,
      MISSING: items.filter((g) => g.kind === 'MISSING').length,
      NICE: items.filter((g) => g.kind === 'NICE').length,
    } as Record<GapKind | 'ALL', number>;
  });

  load(): void {
    this._loading.set(true);
    this._error.set(null);
    this.api.listGaps().subscribe({
      next: (items) => {
        this._items.set(items);
        this._loading.set(false);
      },
      error: (err: unknown) => {
        this._loading.set(false);
        this._error.set(problemMessage(err, 'The skill gaps could not be loaded.'));
      },
    });
  }

  create(text: string, kind: GapKind, note: string): void {
    this._saving.set(true);
    this._error.set(null);
    this.api.createGap(text, kind, note).subscribe({
      next: () => {
        this._saving.set(false);
        this.load();
      },
      error: (err: unknown) => {
        this._saving.set(false);
        this.fail(err, 'The skill gap could not be added.');
      },
    });
  }

  /** Optimistically applies the patch, then confirms with a reload (counts are server-computed). */
  update(id: string, patch: GapPatch): void {
    const previous = this._items();
    this._items.update((items) => items.map((g) => (g.id === id ? { ...g, ...patch } : g)));
    this._error.set(null);
    this.api.updateGap(id, patch).subscribe({
      next: () => this.load(),
      error: (err: unknown) => {
        this._items.set(previous);
        this.fail(err, 'The skill gap could not be saved.');
      },
    });
  }

  remove(id: string): void {
    const previous = this._items();
    this._items.update((items) => items.filter((g) => g.id !== id));
    this._error.set(null);
    this.api.deleteGap(id).subscribe({
      next: () => this.load(),
      error: (err: unknown) => {
        this._items.set(previous);
        this.fail(err, 'The skill gap could not be removed.');
      },
    });
  }

  private fail(err: unknown, fallback: string): void {
    const message = problemMessage(err, fallback);
    this._error.set(message);
    this.toast.show(message);
  }

  /** Loads the grouped + ranked skills (docs/API.md §9); does not trigger regrouping. */
  loadClusters(): void {
    this._clustersLoading.set(true);
    this._clustersError.set(null);
    this.api.listSkillClusters().subscribe({
      next: (state) => {
        this._clusters.set(state.clusters);
        this._lastRunAt.set(state.lastRunAt);
        this._clustersLoading.set(false);
      },
      error: (err: unknown) => {
        this._clustersLoading.set(false);
        this._clustersError.set(problemMessage(err, 'The grouped skills could not be loaded.'));
      },
    });
  }

  /** Runs the regrouping synchronously and shows the refreshed clusters (docs/API.md §9). */
  public rebuildClusters(): void {
    this._rebuilding.set(true);
    this._clustersError.set(null);
    this.api.rebuildSkillClusters().subscribe({
      next: (state) => {
        this._clusters.set(state.clusters);
        this._lastRunAt.set(state.lastRunAt);
        this._rebuilding.set(false);
      },
      error: (err: unknown) => {
        this._rebuilding.set(false);
        const message = problemMessage(err, 'The skills could not be regrouped.');
        this._clustersError.set(message);
        this.toast.show(message);
      },
    });
  }

  /** Renames one grouped skill's label (docs/API.md §9); reconciled with the refreshed payload. */
  renameCluster(id: string, label: string): void {
    const previous = this._clusters();
    this._clusters.update((list) => list.map((c) => (c.id === id ? { ...c, label } : c)));
    this._clustersError.set(null);
    this.api.renameSkillCluster(id, label).subscribe({
      next: (state) => {
        this._clusters.set(state.clusters);
        this._lastRunAt.set(state.lastRunAt);
      },
      error: (err: unknown) => {
        this._clusters.set(previous);
        this.failClusters(err, 'The skill label could not be saved.');
      },
    });
  }

  /** Removes a grouped skill; its gaps go too, so both the clusters and the list are reloaded (§9). */
  removeCluster(id: string): void {
    const previous = this._clusters();
    this._clusters.update((list) => list.filter((c) => c.id !== id));
    this._clustersError.set(null);
    this.api.deleteSkillCluster(id).subscribe({
      next: () => {
        this.load();
        this.loadClusters();
      },
      error: (err: unknown) => {
        this._clusters.set(previous);
        this.failClusters(err, 'The grouped skill could not be removed.');
      },
    });
  }

  private failClusters(err: unknown, fallback: string): void {
    const message = problemMessage(err, fallback);
    this._clustersError.set(message);
    this.toast.show(message);
  }
}
