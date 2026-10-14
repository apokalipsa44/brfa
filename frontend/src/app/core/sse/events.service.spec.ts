import { HttpTestingController } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { EventsService } from './events.service';
import { AnalysesStore } from '../analyses.store';
import { expectCvContext, seedAnalyses } from '../../testing/workspace';

/** A controllable EventSource stand-in simulating named SSE frames. */
class FakeEventSource {
  static last: FakeEventSource | null = null;

  readonly listeners = new Map<string, Set<(event: Event) => void>>();

  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;

  closed = false;

  constructor(readonly url: string) {
    FakeEventSource.last = this;
  }

  addEventListener(name: string, listener: (event: Event) => void): void {
    let bucket = this.listeners.get(name);
    if (!bucket) {
      bucket = new Set();
      this.listeners.set(name, bucket);
    }
    bucket.add(listener);
  }

  removeEventListener(name: string, listener: (event: Event) => void): void {
    this.listeners.get(name)?.delete(listener);
  }

  close(): void {
    this.closed = true;
  }

  dispatch(name: string, data: unknown): void {
    const event = { data: JSON.stringify(data) } as MessageEvent<string>;
    this.listeners.get(name)?.forEach((listener) => listener(event));
  }
}

describe('EventsService', () => {
  let service: EventsService;
  let analyses: AnalysesStore;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.resetTestingModule();
    FakeEventSource.last = null;
    (globalThis as unknown as { EventSource: unknown }).EventSource = FakeEventSource;
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(EventsService);
    analyses = TestBed.inject(AnalysesStore);
    http = TestBed.inject(HttpTestingController);
    expectCvContext(http);
    seedAnalyses(http);
    service.disconnect();
  });

  afterEach(() => http.verify());

  it('opens exactly one stream for the whole app', () => {
    service.connect();
    service.connect();

    expect(FakeEventSource.last?.url).toBe('/api/events');
  });

  it('routes analysis.updated into the store', () => {
    service.connect();
    FakeEventSource.last?.dispatch('analysis.updated', {
      id: 'a1',
      title: 'Acme · Java',
      stage: 'AWAITING_REVIEW',
      score: 58,
      progress: 40,
      createdAt: '2026-10-12T10:00:00Z',
      updatedAt: '2026-10-12T10:05:00Z',
    });

    // the store fetches the detail once the analysis lands in AWAITING_REVIEW
    http.expectOne('/api/analyses/a1').flush({});

    expect(analyses.analyses()).toHaveLength(1);
    expect(analyses.analyses()[0].stage).toBe('AWAITING_REVIEW');
  });

  it('routes analysis.deleted into the store', () => {
    service.connect();
    FakeEventSource.last?.dispatch('analysis.deleted', { analysisId: 'a1' });

    expect(analyses.analyses()).toHaveLength(0);
  });

  it('refetches the model state on models.updated', () => {
    service.connect();
    FakeEventSource.last?.dispatch('models.updated', {});
    http
      .expectOne('/api/llm/models')
      .flush({ models: [], activeModelId: null, ready: false, reason: null });
  });

  it('stays quiet for the heartbeat ping', () => {
    service.connect();
    FakeEventSource.last?.dispatch('ping', {});

    expect(service.disconnected()).toBe(false);
  });

  it('does not flag a disconnect immediately', () => {
    service.connect();
    FakeEventSource.last?.onerror?.();

    expect(service.disconnected()).toBe(false);
  });
});
