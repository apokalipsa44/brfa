import { ChangeDetectionStrategy, Component, inject, input, output } from '@angular/core';

import { CvContextStore } from '../../core/cv-context.store';
import { CvChip } from '../cv-chip/cv-chip';
import { ModelSelect } from '../model-select/model-select';
import { EventsService } from '../../core/sse/events.service';

/**
 * Workspace context bar (docs/API.md §2/§3): the trace indicator, the CV chip and the model picker.
 * The CV and model are persistent context here, never per-submit form input.
 */
@Component({
  selector: 'app-top-bar',
  imports: [CvChip, ModelSelect],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './top-bar.html',
})
export class TopBar {
  protected readonly cv = inject(CvContextStore);
  protected readonly events = inject(EventsService);

  /** The mobile analyses drawer toggle only makes sense on the job-analysis tab. */
  readonly showSidebarToggle = input(true);

  readonly openSidebar = output();
}
