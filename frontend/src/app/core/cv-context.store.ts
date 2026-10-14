import { computed, inject, Injectable, signal } from '@angular/core';
import { WorkspaceApi } from './api/workspace.api';
import { problemMessage } from './error-messages';
import { CvInfo, MAX_CV_BYTES, ModelInfo, ModelState } from './models';

/**
 * Persistent CV + model context (docs/API.md §2, §3). The backend is the source of truth: the CV is
 * uploaded once and reused by every analysis, and the active model is stored server-side. On start
 * the store restores both; live `models.updated` events trigger a refresh. Replacing the CV only
 * affects future analyses (each analysis keeps a snapshot).
 */
@Injectable({ providedIn: 'root' })
export class CvContextStore {
  private readonly api = inject(WorkspaceApi);

  private readonly _cv = signal<CvInfo | null>(null);
  private readonly _models = signal<ModelInfo[]>([]);
  private readonly _activeModelId = signal<string | null>(null);
  private readonly _ready = signal(false);
  private readonly _reason = signal<string | null>(null);
  private readonly _uploading = signal(false);
  private readonly _cvError = signal<string | null>(null);
  private readonly _refreshing = signal(false);
  private readonly _selecting = signal(false);

  readonly cv = this._cv.asReadonly();
  readonly models = this._models.asReadonly();
  readonly activeModelId = this._activeModelId.asReadonly();
  readonly ready = this._ready.asReadonly();
  readonly reason = this._reason.asReadonly();
  readonly uploading = this._uploading.asReadonly();
  readonly cvError = this._cvError.asReadonly();
  readonly refreshing = this._refreshing.asReadonly();
  readonly selecting = this._selecting.asReadonly();

  readonly hasCv = computed(() => this._cv() !== null);
  readonly activeModel = computed(
    () => this._models().find((m) => m.id === this._activeModelId()) ?? null,
  );

  constructor() {
    this.loadCv();
    this.refreshModels();
  }

  /** Re-reads both the CV and the model state from the backend (docs/API.md §2/§3). */
  restore(): void {
    this.loadCv();
    this.refreshModels();
  }

  /** Uploads or replaces the active CV; failures are surfaced through {@link #cvError}. */
  upload(file: File): void {
    const invalid = validateCvFile(file);
    if (invalid) {
      this._cvError.set(invalid);
      return;
    }
    this._cvError.set(null);
    this._uploading.set(true);
    this.api.uploadCv(file).subscribe({
      next: (cv) => {
        this._cv.set(cv);
        this._uploading.set(false);
      },
      error: (err: unknown) => {
        this._uploading.set(false);
        this._cvError.set(problemMessage(err, 'The CV could not be uploaded.'));
      },
    });
  }

  /** Selects the active model server-side and ensures it is loaded in LM Studio (docs/API.md §3). */
  selectModel(modelId: string): void {
    if (!modelId || modelId === this._activeModelId()) return;
    this._selecting.set(true);
    this.api.setActiveModel(modelId).subscribe({
      next: (state) => {
        this.applyModelState(state);
        this._selecting.set(false);
      },
      error: (err: unknown) => {
        this._selecting.set(false);
        this._reason.set(problemMessage(err, 'The model could not be selected.'));
      },
    });
  }

  /** Re-reads the model context; safe to call on every `models.updated` event. */
  refreshModels(): void {
    this._refreshing.set(true);
    this.api.getModels().subscribe({
      next: (state) => {
        this.applyModelState(state);
        this._refreshing.set(false);
      },
      error: () => {
        this._models.set([]);
        this._ready.set(false);
        this._activeModelId.set(null);
        this._reason.set('Cannot reach the backend.');
        this._refreshing.set(false);
      },
    });
  }

  /** Re-reads the active CV, dropping it silently when the backend no longer knows it. */
  private loadCv(): void {
    this.api.getCv().subscribe({
      next: (cv) => this._cv.set(cv),
      error: () => this._cv.set(null),
    });
  }

  private applyModelState(state: ModelState): void {
    this._models.set(state.models ?? []);
    this._activeModelId.set(state.activeModelId ?? null);
    this._ready.set(!!state.ready);
    this._reason.set(state.reason ?? null);
  }
}

function validateCvFile(file: File): string | null {
  const looksPdf = file.type === 'application/pdf' || file.name.toLowerCase().endsWith('.pdf');
  if (!looksPdf) return 'Only PDF files are accepted.';
  if (file.size > MAX_CV_BYTES) return 'The PDF is larger than 5 MB.';
  return null;
}
