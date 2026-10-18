import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * Keyword chips (docs/API.md §4). Matched keywords are neutral/green, missing one amber — the three
 * status colours are the only accent colours besides the primary one.
 */
@Component({
  selector: 'app-keyword-chips',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './keyword-chips.html',
})
export class KeywordChips {
  readonly keywords = input.required<string[]>();
  readonly tone = input<'matched' | 'missing' | 'neutral'>('neutral');
  readonly title = input<string>('');
}
