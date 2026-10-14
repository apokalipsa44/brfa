import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Component, viewChild } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { NewAnalysisForm } from './new-analysis-form';
import { AnalysesStore } from '../../core/analyses.store';
import { CvContextStore } from '../../core/cv-context.store';
import { EventsService } from '../../core/sse/events.service';
import { CV_READY, READY_MODELS } from '../../testing/workspace';

@Component({
  imports: [NewAnalysisForm],
  template: '<app-new-analysis-form />',
})
class Host {
  readonly form = viewChild(NewAnalysisForm);
}

describe('NewAnalysisForm', () => {
  let fixture: ComponentFixture<InstanceType<typeof Host>>;
  let cv: CvContextStore;
  let analyses: AnalysesStore;
  let http: HttpTestingController;

  function submitButton(): HTMLButtonElement {
    const button = fixture.nativeElement.querySelector(
      'button[type="submit"]',
    ) as HTMLButtonElement;
    return button;
  }

  function postingTextarea(): HTMLTextAreaElement {
    return fixture.nativeElement.querySelector('#posting') as HTMLTextAreaElement;
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Host],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    cv = TestBed.inject(CvContextStore);
    analyses = TestBed.inject(AnalysesStore);
    http = TestBed.inject(HttpTestingController);

    http.expectOne('/api/cv').flush('missing', { status: 404, statusText: 'Not Found' });
    http.expectOne('/api/llm/models').flush({ models: [], activeModelId: null, ready: false, reason: null });
    http.expectOne('/api/analyses').flush([]);
    TestBed.inject(EventsService).disconnect();

    fixture = TestBed.createComponent(Host) as unknown as typeof fixture;
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  it('explains that a CV is missing and keeps Analyze disabled', () => {
    expect(submitButton().disabled).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('Upload a CV to continue');
  });

  it('enables Analyze only once CV and model are ready', () => {
    cv.restore();
    http.expectOne('/api/cv').flush(CV_READY);
    http.expectOne('/api/llm/models').flush(READY_MODELS);
    fixture.detectChanges();
    expect(submitButton().disabled).toBe(true);

    postingTextarea().value = 'Senior Java Developer wanted';
    postingTextarea().dispatchEvent(new Event('input'));
    fixture.detectChanges();

    expect(submitButton().disabled).toBe(false);
    expect(fixture.nativeElement.textContent).not.toContain('Upload a CV to continue');
  });

  it('rejects a posting over the length limit', () => {
    cv.restore();
    http.expectOne('/api/cv').flush(CV_READY);
    http.expectOne('/api/llm/models').flush(READY_MODELS);
    fixture.detectChanges();

    postingTextarea().value = 'x'.repeat(20_001);
    postingTextarea().dispatchEvent(new Event('input'));
    fixture.detectChanges();

    expect(submitButton().disabled).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('over 20000 characters');
  });

  it('submits the posting and clears the textarea', () => {
    cv.restore();
    http.expectOne('/api/cv').flush(CV_READY);
    http.expectOne('/api/llm/models').flush(READY_MODELS);
    fixture.detectChanges();

    postingTextarea().value = 'Senior Java Developer wanted';
    postingTextarea().dispatchEvent(new Event('input'));
    fixture.detectChanges();
    submitButton().click();
    fixture.detectChanges();

    const request = http.expectOne('/api/analyses');
    expect(request.request.body).toEqual({ jobPosting: 'Senior Java Developer wanted' });
    request.flush({
      id: 'a1',
      title: 'Acme · Java',
      stage: 'QUEUED',
      score: null,
      progress: 0,
      createdAt: '2026-10-12T10:00:00Z',
      updatedAt: '2026-10-12T10:00:00Z',
    });
    // the store selects the new analysis and fetches its detail
    http.expectOne('/api/analyses/a1').flush({});
    fixture.detectChanges();

    expect(postingTextarea().value).toBe('');
    expect(analyses.selectedId()).toBe('a1');
  });
});
