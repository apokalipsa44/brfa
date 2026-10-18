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
  templateUrl: './cv-chip.html',
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
