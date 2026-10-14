import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/** Match score, 0–100, coloured by range (docs/API.md §4). Purely decorative. */
@Component({
  selector: 'app-score-badge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span [class]="classes()" class="tabular-nums">{{ score() }}</span>`,
})
export class ScoreBadge {
  readonly score = input<number | null>(null);

  /** `lg` for the big review number, `sm` for the sidebar chip. */
  readonly size = input<'sm' | 'lg'>('sm');

  protected readonly classes = computed(() => {
    const value = this.score();
    if (value === null) return 'hidden';
    const tone =
      value >= 70
        ? 'border-emerald-200 bg-emerald-50 text-emerald-700'
        : value >= 40
          ? 'border-amber-200 bg-amber-50 text-amber-700'
          : 'border-red-200 bg-red-50 text-red-700';
    return this.size() === 'lg'
      ? `inline-flex h-20 w-20 items-center justify-center rounded-full border-2 text-3xl font-semibold ${tone}`
      : `inline-flex items-center rounded-full border px-2 py-0.5 text-xs font-semibold ${tone}`;
  });
}
