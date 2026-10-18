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
  templateUrl: './cv-dropzone.html',
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
