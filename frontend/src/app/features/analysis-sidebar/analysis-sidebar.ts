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
  template: `
    <div class="flex h-full min-h-0 flex-col gap-3">
      <button
        type="button"
        class="flex w-full items-center justify-center gap-2 rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white transition-colors duration-200 hover:bg-indigo-700 focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-2 focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-50"
        [disabled]="!cv.hasCv()"
        (click)="newAnalysis.emit()"
      >
        <span aria-hidden="true">+</span> New analysis
      </button>

      @if (analyses.analyses().length === 0) {
        <p
          class="rounded-xl border border-dashed border-slate-300 bg-white px-3 py-6 text-center text-sm text-slate-500"
        >
          No analyses yet. Paste a posting to check your CV.
        </p>
      } @else {
        <div role="list" class="flex min-h-0 flex-col gap-1.5 overflow-y-auto pe-0.5">
          @for (analysis of analyses.analyses(); track analysis.id) {
            <app-analysis-card
              [summary]="analysis"
              [isSelected]="analysis.id === analyses.selectedId()"
              (selected)="onSelect($event)"
              (renamed)="onRename($event)"
              (appliedChanged)="onAppliedChanged($event)"
              (deleted)="analyses.remove($event)"
              (cancelled)="analyses.cancel($event)"
            />
          }
        </div>
      }
    </div>
  `,
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
