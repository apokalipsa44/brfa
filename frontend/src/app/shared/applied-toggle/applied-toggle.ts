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
        class="inline-flex h-8 items-center gap-1.5 rounded-full border px-2.5 text-xs font-medium transition-colors duration-150 focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:outline-none"
        [class]="markerClasses()"
        [attr.aria-pressed]="applied()"
        [attr.aria-label]="text()"
        [title]="text()"
        (click)="toggled.emit(!applied())"
      >
        @if (applied()) {
          <svg
            viewBox="0 0 20 20"
            fill="currentColor"
            class="h-3.5 w-3.5 shrink-0"
            aria-hidden="true"
          >
            <path
              fill-rule="evenodd"
              d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.857-9.809a.75.75 0 00-1.214-.882l-3.483 4.79-1.88-1.88a.75.75 0 10-1.06 1.061l2.5 2.5a.75.75 0 001.137-.089l4-5.5z"
              clip-rule="evenodd"
            />
          </svg>
          <span>Applied</span>
          @if (appliedAt()) {
            <span class="font-normal text-emerald-600"
              >· {{ formatAppliedDate(appliedAt()!) }}</span
            >
          }
        } @else {
          <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="1.5"
            class="h-3.5 w-3.5 shrink-0"
            aria-hidden="true"
          >
            <path stroke-linecap="round" stroke-linejoin="round" d="M12 4.5v15m7.5-7.5h-15" />
          </svg>
          <span>Mark applied</span>
        }
      </button>
    } @else {
      <div class="flex flex-col items-end gap-1">
        <div class="flex items-center gap-2">
          <span class="text-sm text-slate-600">Applied to job</span>
          <button
            type="button"
            role="switch"
            class="relative inline-flex h-5 w-9 shrink-0 items-center rounded-full bg-slate-300 transition-colors duration-150 data-[applied=true]:bg-emerald-600 focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-2 focus-visible:outline-none"
            [attr.data-applied]="applied()"
            [attr.aria-checked]="applied()"
            [attr.aria-label]="text()"
            [title]="text()"
            (click)="toggled.emit(!applied())"
          >
            <span
              aria-hidden="true"
              class="inline-block h-4 w-4 translate-x-0.5 transform rounded-full bg-white transition-transform duration-150 data-[applied=true]:translate-x-4"
              [attr.data-applied]="applied()"
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
