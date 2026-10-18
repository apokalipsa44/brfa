import { ChangeDetectionStrategy, Component, input } from '@angular/core';

import { Requirement, RequirementStatus, RequirementType } from '../../core/models';

/**
 * The posting requirements the analysis was based on (docs/API.md §4): each entry shows how the CV
 * covers it (Met/Partial/Missing) and how important it is (Must-have/Nice to have/Soft). Renders
 * nothing when there are no requirements.
 */
@Component({
  selector: 'app-requirement-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (requirements().length > 0) {
      <div class="mt-4">
        @if (title()) {
          <h3 class="text-sm font-semibold text-slate-900">{{ title() }}</h3>
        }
        <ul class="mt-2 flex flex-col gap-1.5">
          @for (requirement of requirements(); track $index) {
            <li class="flex items-start gap-2 rounded-lg border border-slate-200 bg-white p-2.5">
              <span
                class="mt-0.5 shrink-0 rounded-full border px-2 py-0.5 text-xs font-medium"
                [class]="statusTone(requirement.status)"
              >
                {{ STATUS_LABEL[requirement.status] }}
              </span>
              <span class="min-w-0 flex-1">
                <span class="block text-sm text-slate-700">{{ requirement.text }}</span>
                @if (requirement.cvEvidence) {
                  <span class="mt-0.5 block text-xs text-slate-500"
                    >CV: “{{ requirement.cvEvidence }}”</span
                  >
                }
              </span>
              <span class="mt-0.5 shrink-0 text-xs text-slate-400">{{
                TYPE_LABEL[requirement.type]
              }}</span>
            </li>
          }
        </ul>
      </div>
    }
  `,
})
export class RequirementList {
  readonly requirements = input.required<Requirement[]>();
  readonly title = input<string>('');

  protected readonly STATUS_LABEL = STATUS_LABEL;
  protected readonly TYPE_LABEL = TYPE_LABEL;

  protected statusTone(status: RequirementStatus): string {
    return STATUS_TONE[status];
  }
}

/** Requirement coverage badges (docs/API.md §4). */
const STATUS_LABEL: Record<RequirementStatus, string> = {
  MET: 'Met',
  PARTIAL: 'Partial',
  MISSING: 'Missing',
};

const STATUS_TONE: Record<RequirementStatus, string> = {
  MET: 'border-emerald-200 bg-emerald-50 text-emerald-700',
  PARTIAL: 'border-amber-200 bg-amber-50 text-amber-700',
  MISSING: 'border-red-200 bg-red-50 text-red-700',
};

const TYPE_LABEL: Record<RequirementType, string> = {
  MUST: 'Must-have',
  NICE: 'Nice to have',
  SOFT: 'Soft',
};
