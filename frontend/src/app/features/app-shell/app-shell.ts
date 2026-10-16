import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { AnalysesStore } from '../../core/analyses.store';
import { CvContextStore } from '../../core/cv-context.store';
import { EventsService } from '../../core/sse/events.service';
import { PageLeaveGuard } from '../../core/page-leave-guard';
import { TopBar } from '../top-bar/top-bar';
import { CvDropzone } from '../cv-dropzone/cv-dropzone';
import { AnalysisSidebar } from '../analysis-sidebar/analysis-sidebar';
import { NewAnalysisForm } from '../new-analysis-form/new-analysis-form';
import { AnalysisDetail } from '../analysis-detail/analysis-detail';
import { Toast } from '../../shared/toast/toast';

/**
 * Workspace shell (docs/API.md UI): the top bar holds the persistent CV + model context, the sidebar
 * lists analyses, and the main panel shows the drop zone until a CV exists, then the new analysis
 * form or the selected analysis. The single SSE stream is opened once here, and a running analysis
 * is cancelled if the tab is closed or refreshed.
 */
@Component({
  selector: 'app-shell',
  imports: [TopBar, CvDropzone, AnalysisSidebar, NewAnalysisForm, AnalysisDetail, Toast],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="flex h-dvh flex-col overflow-hidden bg-slate-50 text-slate-900">
      <app-top-bar (openSidebar)="sidebarOpen.set(true)" />

      <div class="mx-auto flex w-full max-w-[1440px] flex-1 gap-5 overflow-hidden px-6 py-4">
        <!-- Sidebar: fixed on large screens, drawer on small ones. -->
        <aside
          class="hidden w-[24rem] shrink-0 flex-col rounded-xl border border-slate-200 bg-white p-3.5 lg:flex xl:w-[27rem]"
          aria-label="Analyses"
        >
          <app-analysis-sidebar (newAnalysis)="newAnalysis()" />
        </aside>

        <!-- Mobile drawer. -->
        @if (sidebarOpen()) {
          <div class="fixed inset-0 z-30 lg:hidden">
            <button
              type="button"
              class="absolute inset-0 bg-slate-900/40"
              aria-label="Close analyses"
              (click)="sidebarOpen.set(false)"
            ></button>
            <aside
              class="relative m-3 flex max-h-[calc(100dvh-1.5rem)] w-[24rem] max-w-[calc(100vw-1.5rem)] flex-col rounded-xl border border-slate-200 bg-white p-3.5"
              aria-label="Analyses"
            >
              <app-analysis-sidebar (newAnalysis)="newAnalysis()" />
            </aside>
          </div>
        }

        <!-- Main panel. -->
        <main class="min-w-0 flex-1 overflow-y-auto">
          @if (!cv.hasCv()) {
            <div class="flex min-h-full items-center justify-center">
              <app-cv-dropzone class="w-full max-w-lg" />
            </div>
          } @else if (analyses.selectedId()) {
            <app-analysis-detail />
          } @else {
            <app-new-analysis-form />
          }
        </main>
      </div>

      <!-- Live region for status changes, plus the single floating toast. -->
      <div aria-live="polite" class="sr-only">
        @if (analyses.selectedSummary(); as summary) {
          {{ summary.title }} — {{ summary.stage }}
        }
      </div>
      <div class="pointer-events-none fixed inset-x-0 bottom-4 z-40 flex justify-center px-4">
        <app-toast />
      </div>
    </div>
  `,
})
export class AppShell {
  protected readonly cv = inject(CvContextStore);
  protected readonly analyses = inject(AnalysesStore);

  private readonly events = inject(EventsService);
  private readonly pageLeaveGuard = inject(PageLeaveGuard);

  protected readonly sidebarOpen = signal(false);

  constructor() {
    this.events.connect();
  }

  protected newAnalysis(): void {
    this.analyses.select(null);
    this.sidebarOpen.set(false);
  }
}
