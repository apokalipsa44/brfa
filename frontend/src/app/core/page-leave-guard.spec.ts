import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { PageLeaveGuard } from './page-leave-guard';
import { AnalysisSummary } from './models';
import { EventsService } from './sse/events.service';
import { expectCvContext, seedAnalyses } from '../testing/workspace';

function summary(id: string, stage: AnalysisSummary['stage']): AnalysisSummary {
  return {
    id,
    title: `Acme · Java ${id}`,
    stage,
    score: null,
    shortDescription: null,
    appliedAt: null,
    progress: 0,
    createdAt: '2026-10-12T10:00:00Z',
    updatedAt: '2026-10-12T10:01:00Z',
    errorCode: null,
    errorMessage: null,
  };
}

function pageHide(persisted: boolean): void {
  const event = new Event('pagehide');
  Object.defineProperty(event, 'persisted', { value: persisted });
  window.dispatchEvent(event);
}

describe('PageLeaveGuard', () => {
  let http: HttpTestingController;
  let beacons: string[];

  beforeEach(() => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    beacons = [];
    Object.defineProperty(navigator, 'sendBeacon', {
      configurable: true,
      value: (url: string) => {
        beacons.push(url);
        return true;
      },
    });
    TestBed.inject(EventsService).disconnect();
    expectCvContext(http);
  });

  afterEach(() => http.verify());

  it('cancels every working analysis when the tab goes away', () => {
    seedAnalyses(http, [summary('a1', 'EVALUATING'), summary('a2', 'COMPLETED')]);
    TestBed.inject(PageLeaveGuard);

    pageHide(false);

    expect(beacons).toEqual(['/api/analyses/a1/cancel']);
  });

  it('leaves a bfcache page alone', () => {
    seedAnalyses(http, [summary('a1', 'EVALUATING')]);
    TestBed.inject(PageLeaveGuard);

    pageHide(true);

    expect(beacons).toEqual([]);
  });
});
