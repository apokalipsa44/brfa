import { HttpTestingController } from '@angular/common/http/testing';

import { CvInfo, ModelState } from '../core/models';

export interface CvSeed {
  /** `null` answers 404 (no CV). */
  cv?: CvInfo | null;
  models?: ModelState;
}

/** Answers the two requests the CV/model store issues on construction (docs/API.md §2/§3). */
export function expectCvContext(http: HttpTestingController, seed: CvSeed = {}): void {
  const cv = seed.cv ?? null;
  const models: ModelState = seed.models ?? {
    models: [],
    activeModelId: null,
    ready: false,
    reason: 'No model selected',
  };

  if (cv === null) {
    http.expectOne('/api/cv').flush('no cv', { status: 404, statusText: 'Not Found' });
  } else {
    http.expectOne('/api/cv').flush(cv);
  }
  http.expectOne('/api/llm/models').flush(models);
}

/** Answers the list request the analyses store issues on construction (docs/API.md §4). */
export function seedAnalyses(http: HttpTestingController, list: unknown[] = []): void {
  http.expectOne('/api/analyses').flush(list);
}

export const READY_MODELS: ModelState = {
  models: [{ id: 'qwen/iq3_s.gguf', loaded: true }],
  activeModelId: 'qwen/iq3_s.gguf',
  ready: true,
  reason: null,
};

export const CV_READY: CvInfo = { id: 'cv-1', fileName: 'cv.pdf', status: 'READY' };
