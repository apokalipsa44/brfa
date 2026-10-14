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
  template: `
    @if (variant() === 'marker') {
      <button
        type="button"
        class="inline-flex h-8 items-center gap-1 rounded-full border px-2.5 text-xs transition-colors duration-150 focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:outline-none"
        [class]="markerClasses()"
        [attr.aria-pressed]="applied()"
        [attr.aria-label]="text()"
        [title]="text()"
        (click)="toggled.emit(!applied())"
      >
        <span aria-hidden="true">{{ applied() ? '✓' : '○' }}</span>
        @if (applied() && appliedAt()) {
          <span>{{ formatAppliedDate(appliedAt()!) }}</span>
        }
      </button>
    } @else {
      <div class="flex flex-col items-end gap-1">
        <div class="flex items-center gap-2">
          <span class="text-sm text-slate-600">Applied to job</span>
          <button
            type="button"
            role="switch"
            class="relative inline-flex h-5 w-9 shrink-0 items-center rounded-full transition-colors duration-150 focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-2 focus-visible:outline-none"
            [class]="applied() ? 'bg-emerald-600' : 'bg-slate-300'"
            [attr.aria-checked]="applied()"
            [attr.aria-label]="text()"
            [title]="text()"
            (click)="toggled.emit(!applied())"
          >
            <span
              aria-hidden="true"
              class="inline-block h-4 w-4 transform rounded-full bg-white transition-transform duration-150"
              [class.translate-x-4]="applied()"
              [class.translate-x-0.5]="!applied()"
            ></span>
          </button>
        </div>

        @if (applied()) {
          @if (picking()) {
            <input
              #dateField
              type="date"
              class="rounded-lg border border-slate-300 px-2 py-1 text-xs text-slate-700 focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:outline-none"
              [value]="appliedAt() ?? today"
              [attr.max]="today"
              aria-label="Applied date"
              (change)="onDatePicked(dateField.value)"
              (blur)="picking.set(false)"
              (keydown.escape)="picking.set(false)"
            />
          } @else {
            <button
              type="button"
              class="text-xs text-slate-500 underline decoration-dotted underline-offset-2 hover:text-slate-700 focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:outline-none"
              [title]="'Applied to job on ' + fullDateLabel()"
              (click)="picking.set(true)"
            >
              {{ fullDateLabel() }}
            </button>
          }
          @if (dateError()) {
            <p class="text-xs text-red-600" role="alert">{{ dateError() }}</p>
          }
        }
      </div>
    }
  `,
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
    if (this.applied()) return date ? `Applied to job on ${formatFullDate(date)}` : 'Applied to job';
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
