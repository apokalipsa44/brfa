import { computed, inject, Injectable, signal } from '@angular/core';

import { GapPatch, WorkspaceApi } from './api/workspace.api';
import { problemMessage } from './error-messages';
import { GapItem, GapKind } from './models';
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

  readonly items = this._items.asReadonly();
  readonly loading = this._loading.asReadonly();
  readonly saving = this._saving.asReadonly();
  readonly error = this._error.asReadonly();

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
}
