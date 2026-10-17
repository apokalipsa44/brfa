import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { AnalysesStore } from './analyses.store';
import { ToastStore } from './toast.store';
import { todayIso } from './format';
import { AnalysisDetail, AnalysisSummary } from './models';
import { EventsService } from './sse/events.service';
import { expectCvContext, seedAnalyses } from '../testing/workspace';

function summary(id: string, overrides: Partial<AnalysisSummary> = {}): AnalysisSummary {
  return {
    id,
    title: `Acme · Java ${id}`,
    stage: 'AWAITING_REVIEW',
    score: 58,
    shortDescription: 'A backend role.',
    appliedAt: null,
    progress: 40,
    createdAt: '2026-10-12T10:00:00Z',
    updatedAt: '2026-10-12T10:01:00Z',
    errorCode: null,
    errorMessage: null,
    ...overrides,
  };
}

function detail(id: string, overrides: Partial<AnalysisDetail> = {}): AnalysisDetail {
  return {
    ...summary(id),
    modelId: 'qwen/iq3_s.gguf',
    sourceUrl: null,
    summary: 'Solid match.',
    requirements: [],
    matchedKeywords: ['Java'],
    missingKeywords: ['Kubernetes'],
    suggestions: [],
    appliedChanges: null,
    resultUrl: null,
    ...overrides,
  };
}

describe('AnalysesStore', () => {
  let store: AnalysesStore;
  let toast: ToastStore;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    store = TestBed.inject(AnalysesStore);
    toast = TestBed.inject(ToastStore);
    http = TestBed.inject(HttpTestingController);
    // EventsService is what opens the stream in the app; the store never does.
    TestBed.inject(EventsService).disconnect();
    // The CV + model context seeds independently of the analysis list.
    expectCvContext(http);
  });

  afterEach(() => http.verify());

  it('loads the list newest first', () => {
    seedAnalyses(http, [
      summary('a1', { createdAt: '2026-10-12T10:00:00Z' }),
      summary('a2', { createdAt: '2026-10-12T11:00:00Z' }),
    ]);

    expect(store.analyses().map((a) => a.id)).toEqual(['a2', 'a1']);
  });

  it('creates an analysis and selects it', () => {
    seedAnalyses(http, []);

    store.create('Senior Java Developer wanted');
    const request = http.expectOne('/api/analyses');
    expect(request.request.body).toEqual({ jobPosting: 'Senior Java Developer wanted' });
    request.flush(summary('a1', { stage: 'QUEUED', score: null, progress: 0 }));

    // selecting a fresh analysis fetches its detail
    http.expectOne('/api/analyses/a1').flush({});

    expect(store.selectedId()).toBe('a1');
    expect(store.creating()).toBe(false);
  });

  it('creates an analysis from a link', () => {
    seedAnalyses(http, []);

    store.createFromUrl('https://example.com/jobs/123');
    const request = http.expectOne('/api/analyses');
    expect(request.request.body).toEqual({ jobPostingUrl: 'https://example.com/jobs/123' });
    request.flush(summary('a1', { stage: 'QUEUED', score: null, progress: 0 }));
    http.expectOne('/api/analyses/a1').flush({});

    expect(store.selectedId()).toBe('a1');
  });

  it('reports a backend error on create instead of throwing', () => {
    seedAnalyses(http, []);

    store.create('some posting');
    http
      .expectOne('/api/analyses')
      .flush(
        { code: 'MODEL_NOT_READY', detail: 'No ready model' },
        { status: 422, statusText: 'Unprocessable Entity' },
      );

    expect(store.creating()).toBe(false);
    expect(store.actionError()).toContain('LM Studio');
  });

  it('sends only the selected suggestion ids when applying', () => {
    seedAnalyses(http, []);

    store.apply('a1', ['s1', 's2']);
    const request = http.expectOne('/api/analyses/a1/apply');
    expect(request.request.body).toEqual({ approvedSuggestionIds: ['s1', 's2'] });
    request.flush(summary('a1', { stage: 'REWRITING', progress: 65 }));

    expect(store.analyses()[0].stage).toBe('REWRITING');
  });

  it('renames through PATCH', () => {
    seedAnalyses(http, [summary('a1')]);

    store.rename('a1', 'My custom title');
    const request = http.expectOne('/api/analyses/a1');
    expect(request.request.method).toBe('PATCH');
    expect(request.request.body).toEqual({ title: 'My custom title' });
    request.flush(summary('a1', { title: 'My custom title' }));

    expect(store.analyses()[0].title).toBe('My custom title');
  });

  it('updates the description through PATCH, optimistically', () => {
    seedAnalyses(http, [summary('a1', { shortDescription: 'Backend role.' })]);

    store.updateDescription('a1', 'My own summary.');
    expect(store.analyses()[0].shortDescription).toBe('My own summary.');

    const request = http.expectOne('/api/analyses/a1');
    expect(request.request.method).toBe('PATCH');
    expect(request.request.body).toEqual({ description: 'My own summary.' });
    request.flush(summary('a1', { shortDescription: 'My own summary.' }));

    expect(store.analyses()[0].shortDescription).toBe('My own summary.');
  });

  it('rolls back an optimistic description change when the save fails', () => {
    seedAnalyses(http, [summary('a1', { shortDescription: 'Backend role.' })]);

    store.updateDescription('a1', 'x'.repeat(201));
    expect(store.analyses()[0].shortDescription).toBe('x'.repeat(201));
    http
      .expectOne('/api/analyses/a1')
      .flush(
        { code: 'DESCRIPTION_TOO_LONG', detail: 'too long' },
        { status: 400, statusText: 'Bad Request' },
      );

    expect(store.analyses()[0].shortDescription).toBe('Backend role.');
    expect(store.actionError()).toContain('200');
  });

  it('clears the description override by sending a blank value', () => {
    seedAnalyses(http, [summary('a1', { shortDescription: 'Mine.' })]);

    store.updateDescription('a1', '   ');
    const request = http.expectOne('/api/analyses/a1');
    expect(request.request.body).toEqual({ description: '' });
    request.flush(summary('a1', { shortDescription: 'From the model.' }));

    expect(store.analyses()[0].shortDescription).toBe('From the model.');
  });

  it('marks applied optimistically with today and confirms from the PATCH response', () => {
    seedAnalyses(http, [summary('a1', { appliedAt: null })]);

    store.setApplied('a1', true);
    expect(store.analyses()[0].appliedAt).toBe(todayIso());

    const request = http.expectOne('/api/analyses/a1');
    expect(request.request.method).toBe('PATCH');
    expect(request.request.body).toEqual({ applied: true });
    request.flush(summary('a1', { appliedAt: todayIso() }));

    expect(store.analyses()[0].appliedAt).toBe(todayIso());
  });

  it('keeps an existing applied date on true and clears it on false', () => {
    seedAnalyses(http, [summary('a1', { appliedAt: '2026-10-01' })]);

    store.setApplied('a1', true);
    expect(store.analyses()[0].appliedAt).toBe('2026-10-01');
    http.expectOne('/api/analyses/a1').flush(summary('a1', { appliedAt: '2026-10-01' }));

    store.setApplied('a1', false);
    expect(store.analyses()[0].appliedAt).toBeNull();
    const request = http.expectOne('/api/analyses/a1');
    expect(request.request.body).toEqual({ applied: false });
    request.flush(summary('a1', { appliedAt: null }));
  });

  it('re-applies after being cleared', () => {
    seedAnalyses(http, [summary('a1', { appliedAt: null })]);

    store.setApplied('a1', true);
    http.expectOne('/api/analyses/a1').flush(summary('a1', { appliedAt: todayIso() }));

    store.setApplied('a1', false);
    http.expectOne('/api/analyses/a1').flush(summary('a1', { appliedAt: null }));
    expect(store.analyses()[0].appliedAt).toBeNull();

    store.setApplied('a1', true);
    const request = http.expectOne('/api/analyses/a1');
    expect(request.request.body).toEqual({ applied: true });
    request.flush(summary('a1', { appliedAt: todayIso() }));

    expect(store.analyses()[0].appliedAt).toBe(todayIso());
  });

  it('keeps the open detail in step with the applied marker', () => {
    seedAnalyses(http, [summary('a1', { appliedAt: null })]);
    store.select('a1');
    http.expectOne('/api/analyses/a1').flush(detail('a1', { appliedAt: null }));

    store.setApplied('a1', true);
    expect(store.selected()?.appliedAt).toBe(todayIso());
    http.expectOne('/api/analyses/a1').flush(summary('a1', { appliedAt: todayIso() }));

    expect(store.analyses()[0].appliedAt).toBe(todayIso());
    expect(store.selected()?.appliedAt).toBe(todayIso());
  });

  it('sets an explicit applied date', () => {
    seedAnalyses(http, [summary('a1', { appliedAt: null })]);

    store.setApplied('a1', '2026-10-01');
    const request = http.expectOne('/api/analyses/a1');
    expect(request.request.body).toEqual({ appliedAt: '2026-10-01' });
    request.flush(summary('a1', { appliedAt: '2026-10-01' }));

    expect(store.analyses()[0].appliedAt).toBe('2026-10-01');
  });

  it('rolls back an optimistic change and toasts when the save fails', () => {
    seedAnalyses(http, [summary('a1', { appliedAt: null })]);

    store.setApplied('a1', true);
    expect(store.analyses()[0].appliedAt).toBe(todayIso());
    http
      .expectOne('/api/analyses/a1')
      .flush(
        { code: 'APPLIED_AT_FUTURE', detail: 'future' },
        { status: 422, statusText: 'Unprocessable Entity' },
      );

    expect(store.analyses()[0].appliedAt).toBeNull();
    expect(store.actionError()).toContain('future');
    expect(toast.message()).toContain('future');
  });

  it('drops an analysis deleted by another tab', () => {
    seedAnalyses(http, [summary('a1')]);

    store.onAnalysisDeleted('a1');
    expect(store.analyses()).toHaveLength(0);
  });

  it('toasts when a different analysis completes', () => {
    seedAnalyses(http, [summary('a1', { stage: 'REWRITING' }), summary('a2')]);
    store.select('a2');
    http.expectOne('/api/analyses/a2').flush(detail('a2'));

    store.onAnalysisUpdated(summary('a1', { stage: 'COMPLETED', progress: 100 }));

    expect(toast.message()).toContain('ready');
  });

  it('loads the detail of a newly selected analysis', () => {
    seedAnalyses(http, [summary('a1')]);

    store.select('a1');
    http.expectOne('/api/analyses/a1').flush(detail('a1'));

    expect(store.selected()?.score).toBe(58);
  });

  it('retries a failed analysis', () => {
    seedAnalyses(http, [
      summary('a1', { stage: 'FAILED', errorCode: 'LLM_INVALID_OUTPUT', errorMessage: 'bad json' }),
    ]);

    store.retry('a1');
    const request = http.expectOne('/api/analyses/a1/retry');
    expect(request.request.method).toBe('POST');
    request.flush(summary('a1', { stage: 'EVALUATING', progress: 15 }));
    // retry also refreshes the detail
    http.expectOne('/api/analyses/a1').flush(detail('a1', { stage: 'EVALUATING' }));

    expect(store.analyses()[0].stage).toBe('EVALUATING');
  });

  it('cancels a running analysis', () => {
    seedAnalyses(http, [summary('a1', { stage: 'EVALUATING' })]);

    store.cancel('a1');
    const request = http.expectOne('/api/analyses/a1/cancel');
    expect(request.request.method).toBe('POST');
    request.flush(summary('a1', { stage: 'CANCELLED' }));
    // cancel also refreshes the detail
    http.expectOne('/api/analyses/a1').flush(detail('a1', { stage: 'CANCELLED' }));

    expect(store.analyses()[0].stage).toBe('CANCELLED');
  });

  it('reports a backend error on cancel instead of throwing', () => {
    seedAnalyses(http, [summary('a1', { stage: 'AWAITING_REVIEW' })]);

    store.cancel('a1');
    http
      .expectOne('/api/analyses/a1/cancel')
      .flush('not cancellable', { status: 409, statusText: 'Conflict' });

    expect(store.actionError()).not.toBeNull();
  });
});
