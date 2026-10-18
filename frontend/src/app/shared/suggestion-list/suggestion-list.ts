import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { Priority, Suggestion } from '../../core/models';

const PRIORITY_LABEL: Record<Priority, string> = {
  HIGH: 'High priority',
  MEDIUM: 'Medium priority',
  LOW: 'Low priority',
};

const PRIORITY_TONE: Record<Priority, string> = {
  HIGH: 'border-red-200 bg-red-50 text-red-700',
  MEDIUM: 'border-amber-200 bg-amber-50 text-amber-700',
  LOW: 'border-slate-200 bg-slate-50 text-slate-500',
};

/**
 * The suggested changes from stage 1 (docs/API.md §4). Each row is a checkbox (checked by default for
 * high and medium priority), the affected section, the problem, the proposal with before/after text
 * when the evaluation provided it, and a priority tag.
 */
@Component({
  selector: 'app-suggestion-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './suggestion-list.html',
})
export class SuggestionList {
  readonly suggestions = input.required<Suggestion[]>();

  /** Set of suggestion ids the user has approved. */
  readonly selectedIds = input.required<ReadonlySet<string>>();

  readonly toggled = output<string>();

  protected readonly PRIORITY_LABEL = PRIORITY_LABEL;

  protected isSelected(id: string): boolean {
    return this.selectedIds().has(id);
  }

  protected hasTextPair(suggestion: Suggestion): boolean {
    return suggestion.before.trim().length > 0 && suggestion.after.trim().length > 0;
  }

  protected priorityTone(priority: Priority): string {
    return PRIORITY_TONE[priority];
  }
}
