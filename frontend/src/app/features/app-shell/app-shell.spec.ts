import { HttpTestingController } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { AppShell } from './app-shell';
import { CvImprovementState, SkillClusters } from '../../core/models';
import { expectCvContext, seedAnalyses } from '../../testing/workspace';

/** A no-op EventSource stand-in; the shell opens the single SSE stream on construction. */
class FakeEventSource {
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  constructor(readonly url: string) {}
  addEventListener(): void {
    // the shell test never dispatches SSE frames
  }
  removeEventListener(): void {
    // no-op
  }
  close(): void {
    // no-op
  }
}

describe('AppShell', () => {
  let fixture: ComponentFixture<AppShell>;
  let http: HttpTestingController;

  const NO_CLUSTERS: SkillClusters = { running: false, lastRunAt: null, lastError: null, clusters: [] };
  const NO_IMPROVEMENT: CvImprovementState = {
    stage: 'NONE',
    skills: [],
    modelId: null,
    startedAt: null,
    finishedAt: null,
    errorCode: null,
    errorMessage: null,
    resultUrl: null,
  };

  function tab(text: string): HTMLButtonElement {
    return Array.from(fixture.nativeElement.querySelectorAll('[role="tab"]')).find(
      (el) => (el as HTMLElement).textContent?.trim() === text,
    ) as HTMLButtonElement;
  }

  /** Flushes the three requests the gaps curator fires when it mounts. */
  function flushGapsCurator(): void {
    http.expectOne('/api/skill-gaps').flush([]);
    http.expectOne('/api/skill-gaps/clusters').flush(NO_CLUSTERS);
    http.expectOne('/api/cv/improvement').flush(NO_IMPROVEMENT);
  }

  beforeEach(async () => {
    (globalThis as unknown as { EventSource: unknown }).EventSource = FakeEventSource;
    await TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AppShell);
    expectCvContext(http); // no CV yet
    seedAnalyses(http);
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  it('defaults to the job-analysis tab with the analyses sidebar', () => {
    const labels = Array.from(fixture.nativeElement.querySelectorAll('[role="tab"]')).map((el) =>
      (el as HTMLElement).textContent?.trim(),
    );
    expect(labels).toEqual(['Job analysis', 'Gaps curator']);
    expect(tab('Job analysis').getAttribute('aria-selected')).toBe('true');
    expect(fixture.nativeElement.querySelector('app-analysis-sidebar')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('app-skill-gaps')).toBeNull();
  });

  it('shows the gaps curator without the analyses sidebar', () => {
    tab('Gaps curator').click();
    fixture.detectChanges();
    flushGapsCurator();
    fixture.detectChanges();

    expect(tab('Gaps curator').getAttribute('aria-selected')).toBe('true');
    expect(fixture.nativeElement.querySelector('app-analysis-sidebar')).toBeNull();
    expect(fixture.nativeElement.querySelector('app-skill-gaps')).not.toBeNull();
  });

  it('brings the analyses sidebar back when returning to the job-analysis tab', () => {
    tab('Gaps curator').click();
    fixture.detectChanges();
    flushGapsCurator();
    fixture.detectChanges();

    tab('Job analysis').click();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('app-analysis-sidebar')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('app-skill-gaps')).toBeNull();
  });
});
