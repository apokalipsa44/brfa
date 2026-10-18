import {
  ChangeDetectionStrategy,
  Component,
  effect,
  ElementRef,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';

import { formatAppliedDate, formatFullDate, todayIso } from '../../core/format';

/**
 * The "applied to job" marker (docs/API.md §4), shared by the sidebar card and the detail header. The
 * `marker` variant is a compact round toggle showing the applied date; the `switch` variant is a
 * labelled switch whose date opens a native date input (past/today only). Both emit the requested
 * state; the parent owns the request.
 */
@Component({
  selector: 'app-applied-toggle',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './applied-toggle.html',
})
export class AppliedToggle {
  readonly applied = input(false);
  readonly appliedAt = input<string | null>(null);
  readonly variant = input<'marker' | 'switch'>('marker');

  /** Requests a new applied state: true = mark (today), false = clear. */
  readonly toggled = output<boolean>();
  /** Requests an explicit applied date (ISO yyyy-MM-dd). */
  readonly datePicked = output<string>();

  protected readonly picking = signal(false);
  protected readonly dateError = signal<string | null>(null);
  protected readonly today = todayIso();

  private readonly dateField = viewChild<ElementRef<HTMLInputElement>>('dateField');

  constructor() {
    effect(() => {
      if (!this.picking()) return;
      const el = this.dateField()?.nativeElement;
      if (el) el.focus();
    });
  }

  protected readonly formatAppliedDate = formatAppliedDate;

  protected markerClasses(): string {
    return this.applied()
      ? 'border-emerald-200 bg-emerald-50 text-emerald-700'
      : 'border-slate-200 text-slate-400 hover:border-slate-300 hover:text-slate-600';
  }

  /** The marker's accessible name and tooltip. */
  protected text(): string {
    const date = this.appliedAt();
    if (this.applied())
      return date ? `Applied to job on ${formatFullDate(date)}` : 'Applied to job';
    return 'Mark as applied to job';
  }

  protected fullDateLabel(): string {
    const date = this.appliedAt();
    return date ? formatFullDate(date) : '';
  }

  protected onDatePicked(value: string): void {
    if (!value) {
      this.picking.set(false);
      return;
    }
    if (value > this.today) {
      this.dateError.set('The applied date cannot be in the future.');
      return;
    }
    this.dateError.set(null);
    this.picking.set(false);
    this.datePicked.emit(value);
  }
}
