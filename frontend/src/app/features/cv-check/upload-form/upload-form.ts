import { ChangeDetectionStrategy, Component, computed, ElementRef, inject, OnDestroy, OnInit, signal, viewChild } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { CvJobsApi, LoadedModelsInfo, StoredCv } from '../../../core/api/cv-jobs.api';
import { JobsStore } from '../../../core/jobs.store';

const MAX_FILE_BYTES = 5 * 1024 * 1024;
const MAX_DESCRIPTION_CHARS = 20_000;
const CV_STORAGE_KEY = 'cvchecker.cv.v1';

interface StoredCvRef {
  cvId: string;
  fileName: string;
}

/**
 * Upload form (plan §8.4): PDF dropzone + posting textarea + optional LLM model selection.
 * Both the CV and the model are independent of job submission — the CV is uploaded once and
 * reused for every posting (docs/API.md §7); the model is switched in LM Studio (§6). After 202
 * only the posting clears, so the same CV can be submitted against another offer immediately.
 */
@Component({
  selector: 'app-upload-form',
  imports: [ReactiveFormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="rounded-lg border border-gray-200 bg-white p-4 shadow-sm">
      <h2 class="text-lg font-semibold">Check your CV</h2>
      <p class="mt-1 text-sm text-gray-600">
        Upload a PDF CV once and reuse it for every job posting. Analysis runs in the background —
        you can submit more postings while waiting.
      </p>

      @if (models().length > 0) {
        <label class="mt-3 block text-sm font-medium" for="model">LLM model</label>
        <p class="text-xs text-gray-500">Switches the model loaded in LM Studio — independent of the CV upload.</p>
        <select
          id="model"
          [value]="selectedModel()"
          (change)="onModelChange($event)"
          [disabled]="modelSwitching()"
          class="mt-1 w-full rounded-md border border-gray-300 p-2 text-sm shadow-sm disabled:cursor-not-allowed disabled:opacity-50"
        >
          <option value="">-- default model --</option>
          @for (model of models(); track model) {
            <option [value]="model">{{ model }}</option>
          }
        </select>
        @if (modelError(); as message) {
          <p role="alert" class="mt-1 rounded-md bg-red-50 p-2 text-xs text-red-700">{{ message }}</p>
        }
      } @else {
        <p class="mt-3 text-xs text-gray-500">
          Model list unavailable — LM Studio did not answer, so no model can be loaded here.
        </p>
      }

      <p
        class="mt-2 text-xs"
        [class.text-blue-600]="modelSwitching()"
        [class.text-red-600]="!lmReachable() && !modelSwitching()"
        [class.text-amber-600]="lmReachable() && !modelSwitching() && !modelLoaded()"
        [class.text-green-700]="modelLoaded()"
      >
        {{ modelStatus() }}
      </p>

      <div
        class="mt-3 rounded-md border-2 border-dashed p-4 text-center text-sm"
        [class.border-blue-400]="dragOver()"
        [class.bg-blue-50]="dragOver()"
        [class.border-gray-300]="!dragOver()"
        (dragover)="onDragOver($event)"
        (dragleave)="dragOver.set(false)"
        (drop)="onDrop($event)"
      >
        <input
          #fileInput
          type="file"
          accept="application/pdf,.pdf"
          class="hidden"
          (change)="onFilePicked($event)"
        />
        @if (cvUploading()) {
          <p class="text-gray-600">Uploading CV…</p>
        } @else if (currentCv(); as cv) {
          <p class="font-medium">{{ cv.fileName }} <span class="text-gray-500">({{ sizeKb(cv) }} KB)</span></p>
          <p class="mt-1 text-xs text-gray-500">Stored — reused for every job offer until you change it.</p>
          <button type="button" class="mt-1 text-sm text-blue-600 hover:underline" (click)="pickFile()">
            Choose another file
          </button>
        } @else {
          <p class="text-gray-600">Drag a PDF here or</p>
          <button type="button" class="mt-1 text-sm font-medium text-blue-600 hover:underline" (click)="pickFile()">
            choose a file
          </button>
        }
      </div>
      @if (cvError(); as message) {
        <p role="alert" class="mt-2 rounded-md bg-red-50 p-2 text-sm text-red-700">{{ message }}</p>
      }

      <label class="mt-3 block text-sm font-medium" for="posting">Job posting</label>
      <textarea
        id="posting"
        rows="6"
        class="mt-1 w-full rounded-md border border-gray-300 p-2 text-sm"
        placeholder="Paste the job posting text here…"
        [formControl]="posting"
      ></textarea>
      <p class="mt-1 text-right text-xs text-gray-500">{{ posting.value.length }} / {{ maxDescriptionChars }} characters</p>

      @if (error(); as message) {
        <p role="alert" class="mt-2 rounded-md bg-red-50 p-2 text-sm text-red-700">{{ message }}</p>
      }

      <button
        type="button"
        class="mt-3 rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white disabled:cursor-not-allowed disabled:opacity-50"
        [disabled]="uploading() || !canSubmit()"
        (click)="submit()"
      >
        {{ uploading() ? 'Submitting…' : 'Submit for analysis' }}
      </button>
    </section>
  `,
})
export class UploadForm implements OnInit, OnDestroy {
  private readonly api = inject(CvJobsApi);
  private readonly store = inject(JobsStore);

  readonly maxDescriptionChars = MAX_DESCRIPTION_CHARS;
  readonly posting = new FormControl('', { nonNullable: true });
  readonly currentCv = signal<StoredCv | null>(null);
  readonly selectedModel = signal<string>('');
  readonly models = signal<string[]>([]);
  readonly dragOver = signal(false);
  readonly uploading = signal(false);
  readonly error = signal<string | null>(null);
  readonly cvUploading = signal(false);
  readonly cvError = signal<string | null>(null);
  readonly modelSwitching = signal(false);
  readonly modelError = signal<string | null>(null);
  readonly lmReachable = signal(true);
  readonly loadedModels = signal<string[]>([]);
  readonly modelStatus = computed(() => {
    if (this.modelSwitching()) {
      const m = this.selectedModel();
      return m ? `Loading ${m}…` : 'Loading model…';
    }
    if (!this.lmReachable()) return 'LM Studio unavailable — no model can be loaded.';
    if (this.loadedModels().length === 0) return 'No model loaded — choose one above to enable Submit.';
    return `Model loaded in LM Studio: ${this.loadedModels().join(', ')}.`;
  });

  private readonly fileInput = viewChild<ElementRef<HTMLInputElement>>('fileInput');
  private pollTimer = 0;

  ngOnInit(): void {
    this.fetchModels();
    this.restoreCv();
    this.refreshLoadedModels();
    this.pollTimer = window.setInterval(() => this.refreshLoadedModels(), 15_000);
  }

  ngOnDestroy(): void {
    window.clearInterval(this.pollTimer);
  }

  canSubmit(): boolean {
    return this.currentCv() !== null && !this.cvUploading() && this.posting.value.trim().length > 0
      && this.modelLoaded();
  }

  /** Submit requires a model actually loaded in LM Studio — a job cannot run without one. */
  modelLoaded(): boolean {
    return this.lmReachable() && this.loadedModels().length > 0 && !this.modelSwitching();
  }

  pickFile(): void {
    this.fileInput()?.nativeElement.click();
  }

  onDragOver(event: DragEvent): void {
    event.preventDefault();
    this.dragOver.set(true);
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragOver.set(false);
    const file = event.dataTransfer?.files?.[0];
    if (file) this.uploadCv(file);
  }

  onFilePicked(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (file) this.uploadCv(file);
  }

  onModelChange(event: Event): void {
    const value = (event.target as HTMLSelectElement).value;
    this.modelError.set(null);
    if (value === '') {
      // "-- default model --": nothing to load; re-read what LM Studio actually has in memory.
      this.selectedModel.set('');
      this.refreshLoadedModels();
      return;
    }
    const previous = this.selectedModel();
    this.modelSwitching.set(true);
    this.api.loadModel(value).subscribe({
      next: () => {
        this.selectedModel.set(value);
        this.modelSwitching.set(false);
        this.loadedModels.set([value]);
        this.lmReachable.set(true);
      },
      error: (err: unknown) => {
        this.selectedModel.set(previous);
        this.modelSwitching.set(false);
        this.modelError.set(modelProblemMessage(err, value));
        this.refreshLoadedModels(); // the failed switch may already have unloaded the old model
      },
    });
  }

  sizeKb(cv: StoredCv): number {
    return Math.max(1, Math.round(cv.sizeBytes / 1024));
  }

  submit(): void {
    const cv = this.currentCv();
    const description = this.posting.value.trim();
    const validationError = validatePosting(description);
    if (!cv || validationError) {
      this.error.set(validationError ?? 'Upload a PDF CV first.');
      return;
    }
    this.error.set(null);
    this.uploading.set(true);
    // Button re-enables as soon as the short POST finishes (plan §8.4); the job then runs via SSE.
    // Neither the PDF nor the model is sent here: the CV was uploaded once and the model switched
    // in LM Studio beforehand, both independently of this submission.
    this.api.createJob(cv.cvId, description).subscribe({
      next: (response) => {
        this.uploading.set(false);
        this.store.track(response.jobId, cv.fileName);
        this.posting.reset('');
        // The stored CV stays: the next posting can reuse it without re-uploading.
      },
      error: (err: unknown) => {
        this.uploading.set(false);
        this.error.set(problemMessage(err));
      },
    });
  }

  /** Uploads the picked PDF once; it becomes the current CV for every following job offer. */
  private uploadCv(file: File): void {
    const invalid = validatePdf(file);
    if (invalid) {
      this.cvError.set(invalid);
      return;
    }
    this.cvError.set(null);
    this.cvUploading.set(true);
    this.api.uploadCv(file).subscribe({
      next: (cv) => {
        this.currentCv.set(cv);
        this.cvUploading.set(false);
        persistCv(cv);
        const input = this.fileInput()?.nativeElement;
        if (input) input.value = '';
      },
      error: (err: unknown) => {
        this.cvUploading.set(false);
        this.cvError.set(problemMessage(err)); // a failed upload keeps the previously stored CV
      },
    });
  }

  private fetchModels(): void {
    this.api.getAvailableModels().subscribe({
      next: (models) => this.models.set(models),
      error: () => this.models.set([]),
    });
  }

  /** Re-reads what LM Studio has in memory (docs/API.md §6.1); skipped while a switch is in flight. */
  private refreshLoadedModels(): void {
    if (this.modelSwitching()) return;
    this.api.getLoadedModels().subscribe({
      next: (info: LoadedModelsInfo) => {
        this.lmReachable.set(info.reachable);
        this.loadedModels.set(info.loaded);
      },
      error: () => {
        this.lmReachable.set(false);
        this.loadedModels.set([]);
      },
    });
  }

  /** Re-opens the stored CV after a page refresh; the backend may have replaced/cleared it. */
  private restoreCv(): void {
    const ref = readStoredCv();
    if (!ref) return;
    this.api.getCv(ref.cvId).subscribe({
      next: (cv) => this.currentCv.set(cv),
      error: () => clearStoredCv(),
    });
  }
}

function validatePdf(file: File): string | null {
  const looksPdf = file.type === 'application/pdf' || file.name.toLowerCase().endsWith('.pdf');
  if (!looksPdf) return 'Only PDF files are accepted.';
  if (file.size > MAX_FILE_BYTES) return 'The PDF is larger than 5 MB.';
  return null;
}

function validatePosting(description: string): string | null {
  if (description.length === 0) return 'Paste the job posting text.';
  if (description.length > MAX_DESCRIPTION_CHARS)
    return `The job posting has ${description.length} characters; the limit is ${MAX_DESCRIPTION_CHARS}.`;
  return null;
}

/** Shows backend ProblemDetail messages (docs/API.md) instead of raw HTTP text. */
function problemMessage(err: unknown): string {
  if (err instanceof HttpErrorResponse) {
    const body = err.error as { code?: string; detail?: string } | null;
    if (body && typeof body.detail === 'string' && body.detail.length > 0) return body.detail;
    if (err.status === 0) return 'Cannot reach the backend. Is it running on :8080?';
    return `Upload failed (HTTP ${err.status}). Please try again.`;
  }
  return 'Upload failed. Please try again.';
}

/** Error text for the model switcher (docs/API.md §6): prefers the backend detail message. */
function modelProblemMessage(err: unknown, model: string): string {
  if (err instanceof HttpErrorResponse) {
    const body = err.error as { detail?: string } | null;
    if (body && typeof body.detail === 'string' && body.detail.length > 0) return body.detail;
    if (err.status === 0) return 'Cannot reach the backend. Is it running on :8080?';
  }
  return `Could not switch to "${model}". Check the identifier or LM Studio.`;
}

function persistCv(cv: StoredCv): void {
  try {
    localStorage.setItem(CV_STORAGE_KEY, JSON.stringify({ cvId: cv.cvId, fileName: cv.fileName }));
  } catch {
    // Storage unavailable (private mode): the CV still works for this session.
  }
}

function clearStoredCv(): void {
  try {
    localStorage.removeItem(CV_STORAGE_KEY);
  } catch {
    // ignore
  }
}

function readStoredCv(): StoredCvRef | null {
  try {
    const raw = localStorage.getItem(CV_STORAGE_KEY);
    if (!raw) return null;
    const parsed: unknown = JSON.parse(raw);
    if (typeof parsed !== 'object' || parsed === null) return null;
    const cvId = (parsed as StoredCvRef).cvId;
    return typeof cvId === 'string' && cvId.length > 0 ? parsed as StoredCvRef : null;
  } catch {
    return null;
  }
}
