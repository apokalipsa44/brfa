import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  inject,
  signal,
  viewChild,
} from '@angular/core';

import { CvContextStore } from '../../core/cv-context.store';

/**
 * Large CV drop zone (docs/API.md §2). Shown until a CV is uploaded; disappears afterwards. Signals
 * whether the drag-over state is active so the styling stays in the template.
 */
@Component({
  selector: 'app-cv-dropzone',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div
      class="rounded-xl border-2 border-dashed border-slate-300 bg-white p-10 text-center transition-colors duration-200 data-[drag-over=true]:border-indigo-400 data-[drag-over=true]:bg-indigo-50"
      [attr.data-drag-over]="dragOver()"
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
      <p aria-hidden="true" class="text-3xl text-slate-300">⇧</p>
      <h2 class="mt-3 text-lg font-semibold text-slate-900">Upload your CV to start</h2>
      <p class="mx-auto mt-1 max-w-sm text-sm text-slate-500">
        Drag a PDF here, or choose a file. You only do this once — every analysis reuses this CV.
      </p>
      <button
        type="button"
        class="mt-4 rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white transition-colors duration-200 hover:bg-indigo-700 focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-2 focus-visible:outline-none disabled:opacity-50"
        [disabled]="cv.uploading()"
        (click)="pick()"
      >
        {{ cv.uploading() ? 'Reading the PDF…' : 'Choose a file' }}
      </button>
      @if (cv.cvError(); as message) {
        <p role="alert" class="mt-3 text-sm text-red-700">{{ message }}</p>
      }
    </div>
  `,
})
export class CvDropzone {
  protected readonly cv = inject(CvContextStore);

  readonly dragOver = signal(false);

  private readonly fileInput = viewChild<ElementRef<HTMLInputElement>>('fileInput');

  pick(): void {
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
    if (file) this.cv.upload(file);
  }

  onFilePicked(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0];
    const input = event.target as HTMLInputElement;
    if (file) this.cv.upload(file);
    input.value = '';
  }
}
