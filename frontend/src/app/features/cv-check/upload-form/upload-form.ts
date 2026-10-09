import { ChangeDetectionStrategy, Component, ElementRef, inject, signal, viewChild } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { CvJobsApi } from '../../../core/api/cv-jobs.api';
import { JobsStore } from '../../../core/jobs.store';

const MAX_FILE_BYTES = 5 * 1024 * 1024;
const MAX_DESCRIPTION_CHARS = 20_000;

/**
 * Upload form (plan §8.4): PDF dropzone + posting textarea + submit.
 * The button is disabled only for the short upload itself; after 202 the form
 * clears immediately and stays usable while jobs process in the background.
 */
@Component({
  selector: 'app-upload-form',
  imports: [ReactiveFormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="rounded-lg border border-gray-200 bg-white p-4 shadow-sm">
      <h2 class="text-lg font-semibold">Check your CV</h2>
      <p class="mt-1 text-sm text-gray-600">
        Upload a PDF CV and paste the job posting. Analysis runs in the background — you can submit
        more CVs while waiting.
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
        @if (selectedFile(); as file) {
          <p class="font-medium">{{ file.name }} <span class="text-gray-500">({{ fileSizeKb(file) }} KB)</span></p>
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
        {{ uploading() ? 'Uploading…' : 'Submit for analysis' }}
      </button>
    </section>
  `,
})
export class UploadForm {
  private readonly api = inject(CvJobsApi);
  private readonly store = inject(JobsStore);

  readonly maxDescriptionChars = MAX_DESCRIPTION_CHARS;
  readonly posting = new FormControl('', { nonNullable: true });
  readonly selectedFile = signal<File | null>(null);
  readonly dragOver = signal(false);
  readonly uploading = signal(false);
  readonly error = signal<string | null>(null);

  private readonly fileInput = viewChild<ElementRef<HTMLInputElement>>('fileInput');

  canSubmit(): boolean {
    return this.selectedFile() !== null && this.posting.value.trim().length > 0;
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
    if (file) this.setFile(file);
  }

  onFilePicked(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (file) this.setFile(file);
  }

  fileSizeKb(file: File): number {
    return Math.max(1, Math.round(file.size / 1024));
  }

  submit(): void {
    const file = this.selectedFile();
    const description = this.posting.value.trim();
    const validationError = validate(file, description);
    if (validationError) {
      this.error.set(validationError);
      return;
    }
    this.error.set(null);
    this.uploading.set(true);
    // Button re-enables as soon as the short POST finishes (plan §8.4); the job then runs via SSE.
    this.api.createJob(file as File, description).subscribe({
      next: (response) => {
        this.uploading.set(false);
        this.store.track(response.jobId, (file as File).name);
        this.posting.reset('');
        this.selectedFile.set(null);
        const input = this.fileInput()?.nativeElement;
        if (input) input.value = '';
      },
      error: (err: unknown) => {
        this.uploading.set(false);
        this.error.set(problemMessage(err));
      },
    });
  }

  private setFile(file: File): void {
    this.selectedFile.set(file);
    this.error.set(null);
  }
}

function validate(file: File | null, description: string): string | null {
  if (!file) return 'Choose a PDF file first.';
  const looksPdf =
    file.type === 'application/pdf' || file.name.toLowerCase().endsWith('.pdf');
  if (!looksPdf) return 'Only PDF files are accepted.';
  if (file.size > MAX_FILE_BYTES) return 'The PDF is larger than 5 MB.';
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
