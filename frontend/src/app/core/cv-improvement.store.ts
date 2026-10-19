import { computed, inject, Injectable, signal } from '@angular/core';

import { WorkspaceApi } from './api/workspace.api';
import { problemMessage } from './error-messages';
import { CvImprovementState } from './models';
import { ToastStore } from './toast.store';

/**
 * The standalone CV builder (docs/API.md §10): builds an ATS PDF from the active CV plus a
 * user-selected skill list, independent of the analyses. The backend keeps one current build and
 * broadcasts every stage change as `cv.improvement.updated`, so this store also mirrors events
 * pushed from other tabs.
 */
@Injectable({ providedIn: 'root' })
export class CvImprovementStore {
  private readonly api = inject(WorkspaceApi);
  private readonly toast = inject(ToastStore);

  private readonly _state = signal<CvImprovementState | null>(null);
  private readonly _starting = signal(false);

  readonly state = this._state.asReadonly();
  readonly starting = this._starting.asReadonly();

  /** True while the backend is rewriting or rendering. */
  readonly running = computed(() => {
    const stage = this._state()?.stage;
    return stage === 'REWRITING' || stage === 'RENDERING';
  });

  /** True once a build completed and its PDF is downloadable. */
  readonly completed = computed(() => this._state()?.stage === 'COMPLETED');

  /** Loads the current build state (docs/API.md §10). */
  load(): void {
    this.api.getCvImprovement().subscribe({
      next: (state) => this._state.set(state),
      error: () => this._state.set(null),
    });
  }

  /** Starts a build that adds the selected skills; the returned state is already `REWRITING`. */
  start(skills: string[]): void {
    if (this._starting() || this.running()) return;
    this._starting.set(true);
    this.api.startCvImprovement(skills).subscribe({
      next: (state) => {
        this._starting.set(false);
        this._state.set(state);
      },
      error: (err: unknown) => {
        this._starting.set(false);
        const message = problemMessage(err, 'The CV could not be built.');
        this.toast.show(message);
      },
    });
  }

  /** Mirrors a `cv.improvement.updated` event (another tab or this one's ongoing build). */
  onUpdated(state: CvImprovementState): void {
    this._state.set(state);
  }
}
