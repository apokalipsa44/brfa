import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { CvContextStore } from './cv-context.store';
import { CV_READY, READY_MODELS, expectCvContext } from '../testing/workspace';

describe('CvContextStore', () => {
  let store: CvContextStore;
  let http: HttpTestingController;

  beforeEach(async () => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    store = TestBed.inject(CvContextStore);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('restores CV and models from the backend on start', () => {
    expectCvContext(http, { cv: CV_READY, models: READY_MODELS });

    expect(store.hasCv()).toBe(true);
    expect(store.cv()?.fileName).toBe('cv.pdf');
    expect(store.ready()).toBe(true);
    expect(store.activeModelId()).toBe('qwen/iq3_s.gguf');
  });

  it('treats a missing CV as no CV rather than an error', () => {
    expectCvContext(http, { cv: null, models: READY_MODELS });

    expect(store.hasCv()).toBe(false);
    expect(store.cv()).toBeNull();
    expect(store.cvError()).toBeNull();
  });

  it('uploads a replacement CV', () => {
    expectCvContext(http, { cv: CV_READY, models: READY_MODELS });

    const file = new File(['x'], 'new.pdf', { type: 'application/pdf' });
    store.upload(file);

    const request = http.expectOne('/api/cv');
    expect(request.request.method).toBe('POST');
    request.flush({ id: 'cv-2', fileName: 'new.pdf', status: 'READY' });

    expect(store.cv()?.fileName).toBe('new.pdf');
    expect(store.cvError()).toBeNull();
  });

  it('rejects an oversized file before any request', () => {
    expectCvContext(http, { cv: CV_READY, models: READY_MODELS });

    const big = new File(['x'], 'big.pdf', { type: 'application/pdf' });
    Object.defineProperty(big, 'size', { value: 6 * 1024 * 1024 });
    store.upload(big);

    expect(store.uploading()).toBe(false);
    expect(store.cvError()).toContain('5 MB');
    http.expectNone('/api/cv');
  });

  it('selects a model over PUT /api/llm/active-model', () => {
    expectCvContext(http, { cv: null, models: READY_MODELS });

    store.selectModel('gemma-4-e4b');
    const request = http.expectOne('/api/llm/active-model');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({ modelId: 'gemma-4-e4b' });
    request.flush(READY_MODELS);

    expect(store.activeModelId()).toBe('qwen/iq3_s.gguf');
  });

  it('keeps a friendly reason when the model list cannot be reached', () => {
    expectCvContext(http, {
      cv: null,
      models: {
        models: READY_MODELS.models,
        activeModelId: 'qwen/iq3_s.gguf',
        ready: false,
        reason: 'The selected model is not loaded',
      },
    });

    expect(store.ready()).toBe(false);
    expect(store.reason()).toContain('not loaded');
  });
});
