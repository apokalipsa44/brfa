import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Component, viewChild } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { AnalysisDetail } from './analysis-detail';
import { AnalysesStore } from '../../core/analyses.store';
import { CvContextStore } from '../../core/cv-context.store';
import { EventsService } from '../../core/sse/events.service';
import { expectCvContext, seedAnalyses } from '../../testing/workspace';
import { AnalysisDetail as Detail, Suggestion } from '../../core/models';

const SUGGESTIONS: Suggestion[] = [
  {
    id: 's1',
    section: 'Skills',
    problem: 'Kubernetes is missing.',
    proposal: 'Group the container skills.',
    before: 'Docker',
    after: 'Docker, Kubernetes',
    priority: 'HIGH',
  },
  {
    id: 's2',
    section: 'Summary',
    problem: 'The summary is generic.',
    proposal: 'Name the stack explicitly.',
    before: '',
    after: '',
    priority: 'MEDIUM',
  },
  {
    id: 's3',
    section: 'Languages',
    problem: 'Reorder the language list.',
    proposal: 'Put the strongest language first.',
    before: 'English',
    after: 'English (C1)',
    priority: 'LOW',
  },
];

function detailResponse(overrides: Partial<Detail> = {}): Detail {
  return {
    id: 'a1',
    title: 'Acme · Senior Java Developer',
    stage: 'AWAITING_REVIEW',
    score: 58,
    progress: 40,
    createdAt: '2026-10-12T10:00:00Z',
    updatedAt: '2026-10-12T10:05:00Z',
    modelId: 'qwen/iq3_s.gguf',
    summary: 'Good stack fit.',
    matchedKeywords: ['Java'],
    missingKeywords: ['Kubernetes'],
    suggestions: SUGGESTIONS,
    appliedChanges: null,
    resultUrl: null,
    errorCode: null,
    errorMessage: null,
    ...overrides,
  };
}

/** Host that exposes the AnalysisDetail instance to the test. */
@Component({
  imports: [AnalysisDetail],
  template: '<app-analysis-detail />',
})
class Host {
  readonly detail = viewChild(AnalysisDetail);
}

describe('AnalysisDetail review', () => {
  let host: InstanceType<typeof Host>;
  let fixture: ComponentFixture<InstanceType<typeof Host>>;
  let analyses: AnalysesStore;
  let http: HttpTestingController;

  /** Selects an analysis and serves both cache misses so the detail is available. */
  function selectAnalysis(overrides: Partial<Detail> = {}): void {
    const id = overrides.id ?? 'a1';
    analyses.select(id);
    fixture.detectChanges();
    http.expectOne(`/api/analyses/${id}`).flush(detailResponse(overrides));
    fixture.detectChanges();
  }

  function buttonFor(label: string): HTMLButtonElement {
    const buttons = Array.from(
      fixture.nativeElement.querySelectorAll('button'),
    ) as HTMLButtonElement[];
    const found = buttons.find((button) => button.textContent?.includes(label));
    if (!found) throw new Error(`button "${label}" not found`);
    return found;
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Host],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    analyses = TestBed.inject(AnalysesStore);
    TestBed.inject(CvContextStore);
    http = TestBed.inject(HttpTestingController);

    const created = TestBed.createComponent(Host);
    fixture = created as unknown as typeof fixture;
    host = created.componentInstance;
    expectCvContext(http);
    seedAnalyses(http);
    TestBed.inject(EventsService).disconnect();
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  it('checks high and medium priority by default and disables apply when empty', () => {
    selectAnalysis();

    expect(host.detail()?.selectedIds()).toEqual(new Set(['s1', 's2']));
    expect(buttonFor('Apply selected changes').disabled).toBe(false);
    expect(buttonFor('Apply selected changes').textContent).toContain('2 of 3 selected');

    host.detail()?.selectNone();
    fixture.detectChanges();
    expect(buttonFor('Apply selected changes').disabled).toBe(true);
  });

  it('applies only the checked suggestions', () => {
    selectAnalysis();

    host.detail()?.toggleSuggestion('s2');
    fixture.detectChanges();

    buttonFor('Apply selected changes').click();
    const request = http.expectOne('/api/analyses/a1/apply');
    expect(request.request.body).toEqual({ approvedSuggestionIds: ['s1'] });
    request.flush({ ...detailResponse(), stage: 'REWRITING', progress: 65 });
  });

  it('saves an inline title edit on Enter', () => {
    selectAnalysis();

    host.detail()?.startEdit(host.detail()?.detail() ?? detailResponse());
    fixture.detectChanges();

    const input = fixture.nativeElement.querySelector('input[type="text"]') as HTMLInputElement;
    expect(input).toBeTruthy();

    input.value = 'Interview at Globex';
    input.dispatchEvent(new Event('input'));
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    fixture.detectChanges();

    const request = http.expectOne('/api/analyses/a1');
    expect(request.request.method).toBe('PATCH');
    expect(request.request.body).toEqual({ title: 'Interview at Globex' });
    request.flush({ ...detailResponse(), title: 'Interview at Globex' });
  });

  it('cancels the inline title edit on Escape without a request', () => {
    selectAnalysis();

    host.detail()?.startEdit(detailResponse());
    fixture.detectChanges();

    const input = fixture.nativeElement.querySelector('input[type="text"]') as HTMLInputElement;
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('input[type="text"]')).toBeNull();
    http.expectNone('/api/analyses/a1');
  });

  it('shows a retry button with friendly text for a failed analysis', () => {
    selectAnalysis({ stage: 'FAILED', errorCode: 'LLM_INVALID_OUTPUT', errorMessage: 'bad json' });

    const button = buttonFor('Retry');
    button.click();
    http.expectOne('/api/analyses/a1/retry').flush({ ...detailResponse(), stage: 'EVALUATING' });
    http.expectOne('/api/analyses/a1').flush(detailResponse({ stage: 'EVALUATING' }));
  });
});
