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
  template: `
    <ul class="flex flex-col gap-2">
      @for (suggestion of suggestions(); track suggestion.id) {
        <li
          class="rounded-xl border border-slate-200 bg-white p-3 data-[selected=true]:ring-1 data-[selected=true]:ring-indigo-500"
          [attr.data-selected]="isSelected(suggestion.id)"
        >
          <label class="flex cursor-pointer items-start gap-3">
            <input
              type="checkbox"
              class="mt-0.5 h-4 w-4 shrink-0 rounded border-slate-300 accent-indigo-600"
              [checked]="isSelected(suggestion.id)"
              (change)="toggled.emit(suggestion.id)"
              [attr.aria-label]="'Apply change in ' + suggestion.section"
            />
            <span class="min-w-0 flex-1">
              <span class="flex flex-wrap items-center gap-2">
                <span class="text-sm font-medium text-slate-900">{{ suggestion.section }}</span>
                <span
                  class="rounded-full border px-2 py-0.5 text-xs font-medium"
                  [class]="priorityTone(suggestion.priority)"
                >
                  {{ PRIORITY_LABEL[suggestion.priority] }}
                </span>
              </span>
              <span class="mt-1 block text-sm text-slate-600">{{ suggestion.problem }}</span>
              <span class="mt-1 block text-sm text-slate-700">{{ suggestion.proposal }}</span>

              @if (suggestion.needsConfirmation) {
                <span class="mt-1 block text-xs font-medium text-amber-700">
                  ⚠ Review the wording — it mentions something not found in your CV.
                </span>
              }

              @if (hasTextPair(suggestion)) {
                <span class="mt-2 flex min-w-0 flex-col gap-1 text-xs">
                  <span class="block min-w-0 break-words text-slate-500 line-through">{{
                    suggestion.before
                  }}</span>
                  <span class="block min-w-0 break-words text-emerald-700">{{
                    suggestion.after
                  }}</span>
                </span>
              }
            </span>
          </label>
        </li>
      }
    </ul>
  `,
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
