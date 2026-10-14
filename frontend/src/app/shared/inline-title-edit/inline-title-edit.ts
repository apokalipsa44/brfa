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
  template: `
    <input
      #field
      type="text"
      class="w-full rounded-lg border border-indigo-500 bg-white px-2 py-1 text-slate-900 focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:outline-none"
      [class.text-xl]="size() === 'lg'"
      [class.font-semibold]="size() === 'lg'"
      [class.text-sm]="size() === 'sm'"
      [value]="value()"
      [attr.maxlength]="maxLength()"
      [attr.aria-label]="ariaLabel()"
      (keydown.enter)="commit(field)"
      (keydown.escape)="cancel()"
      (blur)="onBlur(field)"
    />
  `,
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
