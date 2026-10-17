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
          class="rounded-full border px-2 py-0.5 text-xs data-[tone=matched]:border-emerald-200 data-[tone=matched]:bg-emerald-50 data-[tone=matched]:text-emerald-700 data-[tone=neutral]:border-slate-200 data-[tone=neutral]:bg-slate-50 data-[tone=neutral]:text-slate-600 data-[tone=missing]:border-amber-200 data-[tone=missing]:bg-amber-50 data-[tone=missing]:text-amber-700"
          [attr.data-tone]="tone()"
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
