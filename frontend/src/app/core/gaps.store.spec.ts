import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { GapsStore } from './gaps.store';
import { ToastStore } from './toast.store';
import { GapItem } from './models';

function gap(id: string, overrides: Partial<GapItem> = {}): GapItem {
  return {
    id,
    text: `Gap ${id}`,
    kind: 'MISSING',
    status: 'OPEN',
    note: '',
    origin: 'DERIVED',
    occurrences: 1,
    sources: [],
    createdAt: '2026-10-18T10:00:00Z',
    updatedAt: '2026-10-18T10:00:00Z',
    ...overrides,
  };
}

describe('GapsStore', () => {
  let store: GapsStore;
  let toast: ToastStore;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    store = TestBed.inject(GapsStore);
    toast = TestBed.inject(ToastStore);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('loads the aggregated list and counts', () => {
    store.load();
    http.expectOne('/api/skill-gaps').flush([
      gap('g1', { kind: 'MISSING' }),
      gap('g2', { kind: 'NICE' }),
      gap('g3', { kind: 'MISSING' }),
    ]);

    expect(store.items()).toHaveLength(3);
    expect(store.counts()).toEqual({ ALL: 3, MISSING: 2, NICE: 1 });
    expect(store.loading()).toBe(false);
  });

  it('creates a gap then reloads to refresh counts', () => {
    store.create('GraphQL', 'NICE', '');
    const request = http.expectOne('/api/skill-gaps');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ text: 'GraphQL', kind: 'NICE', note: '' });
    request.flush(gap('g1', { text: 'GraphQL', origin: 'MANUAL', occurrences: 0 }));

    http.expectOne('/api/skill-gaps').flush([gap('g1', { text: 'GraphQL', origin: 'MANUAL' })]);

    expect(store.items()).toHaveLength(1);
    expect(store.saving()).toBe(false);
  });

  it('updates a gap optimistically and reloads', () => {
    store.load();
    http.expectOne('/api/skill-gaps').flush([gap('g1', { status: 'OPEN' })]);

    store.update('g1', { status: 'LEARNED' });
    expect(store.items()[0].status).toBe('LEARNED');

    const request = http.expectOne('/api/skill-gaps/g1');
    expect(request.request.method).toBe('PATCH');
    request.flush(gap('g1', { status: 'LEARNED' }));
    http.expectOne('/api/skill-gaps').flush([gap('g1', { status: 'LEARNED' })]);

    expect(store.items()[0].status).toBe('LEARNED');
  });

  it('rolls back an optimistic update when the save fails', () => {
    store.load();
    http.expectOne('/api/skill-gaps').flush([gap('g1', { status: 'OPEN' })]);

    store.update('g1', { text: 'x'.repeat(301) });
    expect(store.items()[0].text).toBe('x'.repeat(301));
    http
      .expectOne('/api/skill-gaps/g1')
      .flush(
        { code: 'GAP_TEXT_TOO_LONG', detail: 'too long' },
        { status: 400, statusText: 'Bad Request' },
      );

    expect(store.items()[0].text).toBe('Gap g1');
    expect(store.error()).toContain('300');
    expect(toast.message()).toContain('300');
  });

  it('removes a gap optimistically', () => {
    store.load();
    http.expectOne('/api/skill-gaps').flush([gap('g1'), gap('g2')]);

    store.remove('g1');
    expect(store.items().map((g) => g.id)).toEqual(['g2']);

    http.expectOne('/api/skill-gaps/g1').flush(null, { status: 204, statusText: 'No Content' });
    http.expectOne('/api/skill-gaps').flush([gap('g2')]);
  });
});
