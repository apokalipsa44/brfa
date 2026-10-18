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
  templateUrl: './requirement-list.html',
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
