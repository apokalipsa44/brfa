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
  templateUrl: './app-shell.html',
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
