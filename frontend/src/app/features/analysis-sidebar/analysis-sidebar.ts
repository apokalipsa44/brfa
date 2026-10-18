import { ChangeDetectionStrategy, Component, inject, output } from '@angular/core';

import { AnalysesStore } from '../../core/analyses.store';
import { CvContextStore } from '../../core/cv-context.store';
import { AnalysisCard } from './analysis-card';

/**
 * Analyses list (docs/API.md §4): the New analysis action plus one card per analysis, newest first.
 * Cards update live from the single SSE stream; inline rename and the applied marker write straight
 * through the store's optimistic actions.
 */
@Component({
  selector: 'app-analysis-sidebar',
  imports: [AnalysisCard],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './analysis-sidebar.html',
})
export class AnalysisSidebar {
  readonly analyses = inject(AnalysesStore);
  protected readonly cv = inject(CvContextStore);

  readonly newAnalysis = output<void>();

  onSelect(id: string): void {
    this.analyses.select(id);
  }

  onRename(event: { id: string; title: string }): void {
    this.analyses.rename(event.id, event.title);
  }

  onAppliedChanged(event: { id: string; applied: boolean }): void {
    this.analyses.setApplied(event.id, event.applied);
  }
}
