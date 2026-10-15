import { ChangeDetectionStrategy, Component, ElementRef, inject, viewChild } from '@angular/core';
import { CvContextStore } from '../../core/cv-context.store';

/**
 * CV chip in the top bar (docs/API.md §2): the file name of the single active CV plus a Replace
 * action. The CV is uploaded once and reused by every analysis until replaced; replacing it does not
 * affect existing analyses (each keeps a snapshot).
 */
@Component({
  selector: 'app-cv-chip',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (cv.cv(); as file) {
      <span
        class="flex max-w-64 min-w-0 items-center gap-2 rounded-lg border border-slate-300 bg-white px-2.5 py-1.5"
      >
        <span aria-hidden="true" class="text-slate-400">⧉</span>
        <span class="truncate text-sm text-slate-800" [title]="file.fileName">{{
          file.fileName
        }}</span>
        <button
          type="button"
          class="shrink-0 rounded-md px-1.5 py-0.5 text-xs font-medium text-indigo-600 hover:bg-indigo-50 focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:outline-none"
          (click)="pick()"
        >
          Replace
        </button>
      </span>
    }
    <input
      #fileInput
      type="file"
      accept="application/pdf,.pdf"
      class="hidden"
      (change)="onFilePicked($event)"
    />
  `,
})
export class CvChip {
  protected readonly cv = inject(CvContextStore);

  private readonly fileInput = viewChild<ElementRef<HTMLInputElement>>('fileInput');

  pick(): void {
    this.fileInput()?.nativeElement.click();
  }

  onFilePicked(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (file) this.cv.upload(file);
    input.value = '';
  }
}
