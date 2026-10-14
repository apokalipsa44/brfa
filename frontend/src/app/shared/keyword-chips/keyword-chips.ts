import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * Keyword chips (docs/API.md §4). Matched keywords are neutral/green, missing one amber — the three
 * status colours are the only accent colours besides the primary one.
 */
@Component({
  selector: 'app-keyword-chips',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (title()) {
      <p class="text-xs font-medium text-slate-500">{{ title() }}</p>
    }
    <ul class="flex flex-wrap gap-1.5">
      @for (keyword of keywords(); track keyword) {
        <li
          class="rounded-full border px-2 py-0.5 text-xs"
          [class.border-emerald-200]="tone() === 'matched'"
          [class.bg-emerald-50]="tone() === 'matched'"
          [class.text-emerald-700]="tone() === 'matched'"
          [class.border-slate-200]="tone() === 'neutral'"
          [class.bg-slate-50]="tone() === 'neutral'"
          [class.text-slate-600]="tone() === 'neutral'"
          [class.border-amber-200]="tone() === 'missing'"
          [class.bg-amber-50]="tone() === 'missing'"
          [class.text-amber-700]="tone() === 'missing'"
        >
          {{ keyword }}
        </li>
      }
    </ul>
  `,
})
export class KeywordChips {
  readonly keywords = input.required<string[]>();
  readonly tone = input<'matched' | 'missing' | 'neutral'>('neutral');
  readonly title = input<string>('');
}
