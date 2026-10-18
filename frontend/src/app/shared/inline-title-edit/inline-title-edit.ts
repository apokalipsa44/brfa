import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  input,
  output,
  viewChild,
} from '@angular/core';

/**
 * Inline analysis-title editor, shared by the sidebar card and the detail header. It renders only the
 * text field; the parent decides when editing starts. Enter saves (empty reverts), Escape cancels and
 * the first blur saves a changed non-empty value or cancels.
 */
@Component({
  selector: 'app-inline-title-edit',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './inline-title-edit.html',
})
export class InlineTitleEdit {
  readonly value = input.required<string>();
  readonly maxLength = input(120);
  readonly ariaLabel = input('Analysis title');
  readonly size = input<'sm' | 'lg'>('sm');

  readonly saved = output<string>();
  readonly cancelled = output<void>();

  private readonly field = viewChild.required<ElementRef<HTMLInputElement>>('field');
  private finished = false;

  constructor() {
    afterNextRender(() => {
      const el = this.field().nativeElement;
      el.focus();
      el.select();
    });
  }

  protected commit(field: HTMLInputElement): void {
    const title = field.value.trim();
    if (!title) {
      this.cancel();
      return;
    }
    this.finished = true;
    this.saved.emit(title);
  }

  protected cancel(): void {
    this.finished = true;
    this.cancelled.emit();
  }

  protected onBlur(field: HTMLInputElement): void {
    if (this.finished) return;
    const title = field.value.trim();
    if (title && title !== this.value()) this.commit(field);
    else this.cancel();
  }
}
