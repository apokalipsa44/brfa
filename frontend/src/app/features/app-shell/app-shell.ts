import { ChangeDetectionStrategy, Component, effect, inject, signal } from '@angular/core';

import { AnalysesStore } from '../../core/analyses.store';
import { CvContextStore } from '../../core/cv-context.store';
import { EventsService } from '../../core/sse/events.service';
import { PageLeaveGuard } from '../../core/page-leave-guard';
import { TopBar } from '../top-bar/top-bar';
import { CvDropzone } from '../cv-dropzone/cv-dropzone';
import { AnalysisSidebar } from '../analysis-sidebar/analysis-sidebar';
import { NewAnalysisForm } from '../new-analysis-form/new-analysis-form';
import { AnalysisDetail } from '../analysis-detail/analysis-detail';
import { SkillGaps } from '../skill-gaps/skill-gaps';
import { Toast } from '../../shared/toast/toast';

/** Main-panel tabs hosted inside the shell (the gaps curator is a panel, not a route). */
type View = 'analysis' | 'gaps';

/**
 * Workspace shell (docs/API.md UI): the top bar holds the persistent CV + model context and a tab bar
 * switches between the job-analysis workspace and the gaps curator. The job-analysis tab keeps the
 * analyses sidebar and shows the drop zone until a CV exists, then the new analysis form or the
 * selected analysis. The single SSE stream is opened once here, and a running analysis is cancelled if
 * the tab is closed or refreshed.
 */
@Component({
  selector: 'app-shell',
  imports: [
    TopBar,
    CvDropzone,
    AnalysisSidebar,
    NewAnalysisForm,
    AnalysisDetail,
    SkillGaps,
    Toast,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './app-shell.html',
})
export class AppShell {
  protected readonly cv = inject(CvContextStore);
  protected readonly analyses = inject(AnalysesStore);

  private readonly events = inject(EventsService);
  private readonly pageLeaveGuard = inject(PageLeaveGuard);

  protected readonly sidebarOpen = signal(false);
  protected readonly view = signal<View>('analysis');

  constructor() {
    this.events.connect();
    // Selecting an analysis always returns to the job-analysis tab.
    effect(() => {
      if (this.analyses.selectedId()) this.view.set('analysis');
    });
  }

  protected setView(view: View): void {
    this.view.set(view);
    this.sidebarOpen.set(false);
  }

  protected newAnalysis(): void {
    this.view.set('analysis');
    this.analyses.select(null);
    this.sidebarOpen.set(false);
  }
}
