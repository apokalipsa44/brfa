import { Component, viewChild } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { SkillGaps } from './skill-gaps';
import { CvContextStore } from '../../core/cv-context.store';
import { CvImprovementStore } from '../../core/cv-improvement.store';
import { CvImprovementState, GapItem, GroupedSkill, SkillClusters } from '../../core/models';
import { CV_READY, READY_MODELS, expectCvContext } from '../../testing/workspace';

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

function cluster(id: string, overrides: Partial<GroupedSkill> = {}): GroupedSkill {
  return {
    id,
    label: `Skill ${id}`,
    importance: 3,
    rationale: 'because',
    occurrences: 2,
    kind: 'MISSING',
    members: [{ text: `Gap ${id}`, kind: 'MISSING', occurrences: 2 }],
    updatedAt: '2026-10-18T10:00:00Z',
    ...overrides,
  };
}

function clusterState(clusters: GroupedSkill[]): SkillClusters {
  return { running: false, lastRunAt: '2026-10-18T10:00:00Z', lastError: null, clusters };
}

function improvement(
  stage: CvImprovementState['stage'],
  overrides: Partial<CvImprovementState> = {},
): CvImprovementState {
  return {
    stage,
    skills: [],
    modelId: null,
    startedAt: null,
    finishedAt: null,
    errorCode: null,
    errorMessage: null,
    resultUrl: null,
    ...overrides,
  };
}

@Component({
  imports: [SkillGaps],
  template: '<app-skill-gaps />',
})
class Host {
  readonly cmp = viewChild(SkillGaps);
}

describe('SkillGaps', () => {
  let fixture: ComponentFixture<Host>;
  let http: HttpTestingController;

  function sectionTitles(): string[] {
    return Array.from(fixture.nativeElement.querySelectorAll('section button span.font-semibold')).map(
      (el) => (el as HTMLElement).textContent?.trim() ?? '',
    );
  }

  function button(text: string): HTMLButtonElement {
    return Array.from(fixture.nativeElement.querySelectorAll('button')).find((el) =>
      (el as HTMLButtonElement).textContent?.includes(text),
    ) as HTMLButtonElement;
  }

  /** Flushes the two requests the component fires on construction (besides the CV-build state). */
  function flushInitial(gaps: GapItem[] = [], clusters: GroupedSkill[] = []): void {
    http.expectOne('/api/skill-gaps').flush(gaps);
    http.expectOne('/api/skill-gaps/clusters').flush(clusterState(clusters));
    fixture.detectChanges();
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Host],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Host);
    // The component loads the CV context, both gap lists and the current build state on construction.
    expectCvContext(http, { cv: CV_READY, models: READY_MODELS });
    http.expectOne('/api/cv/improvement').flush(improvement('NONE'));
  });

  afterEach(() => http.verify());

  it('groups gaps by status and drops empty groups', () => {
    flushInitial([
      gap('g1', { text: 'Kubernetes', status: 'OPEN', kind: 'MISSING' }),
      gap('g2', { text: 'GraphQL', status: 'LEARNED', kind: 'NICE' }),
      gap('g3', { text: 'Terraform', status: 'OPEN', kind: 'NICE' }),
    ]);
    button('Curated list').click();
    fixture.detectChanges();

    expect(sectionTitles()).toEqual(['Open', 'Learned']); // no Planned section
    const openItems = fixture.nativeElement.querySelectorAll('section:first-of-type li');
    expect(openItems.length).toBe(2);
    // Missing terms come before nice-to-haves inside a group (text is an input value).
    const firstText = (openItems[0].querySelector('input[type="text"]') as HTMLInputElement).value;
    expect(firstText).toContain('Kubernetes');
  });

  it('collapses and expands a group', () => {
    flushInitial([gap('g1', { status: 'OPEN' })]);
    button('Curated list').click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('li').length).toBe(1);

    const header = fixture.nativeElement.querySelector('section button') as HTMLButtonElement;
    header.click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('li').length).toBe(0);

    header.click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('li').length).toBe(1);
  });

  it('filters the curated list by kind', () => {
    flushInitial([
      gap('g1', { text: 'Kubernetes', kind: 'MISSING' }),
      gap('g2', { text: 'GraphQL', kind: 'NICE' }),
    ]);
    button('Curated list').click();
    fixture.detectChanges();

    const niceTab = Array.from(
      fixture.nativeElement.querySelectorAll('button'),
    ).find((button) => (button as HTMLButtonElement).textContent?.includes('Nice to have')) as
      | HTMLButtonElement
      | undefined;
    niceTab?.click();
    fixture.detectChanges();

    const items = fixture.nativeElement.querySelectorAll('li');
    expect(items.length).toBe(1);
    const text = (items[0].querySelector('input[type="text"]') as HTMLInputElement).value;
    expect(text).toContain('GraphQL');
  });

  it('shows the grouped skills by default', () => {
    flushInitial([gap('g1')], [cluster('c1', { label: 'Docker & Kubernetes', importance: 5 })]);

    const content = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(content).toContain('Docker & Kubernetes');
    expect(content).toContain('★★★★★');
  });

  it('regroups from the grouped view', () => {
    flushInitial([]);

    button('Regroup now').click();
    fixture.detectChanges();

    const request = http.expectOne('/api/skill-gaps/clusters/rebuild');
    expect(request.request.method).toBe('POST');
    request.flush(clusterState([cluster('c1', { label: 'Kubernetes' })]));
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Kubernetes');
  });

  it('builds an improved CV from the ticked grouped skills', () => {
    flushInitial(
      [gap('g1')],
      [cluster('c1', { label: 'Kubernetes' }), cluster('c2', { label: 'GraphQL' })],
    );

    expect(button('Build improved CV').disabled).toBe(true);

    const checkboxes = fixture.nativeElement.querySelectorAll(
      'input[type="checkbox"]',
    ) as NodeListOf<HTMLInputElement>;
    checkboxes[0].click();
    fixture.detectChanges();
    expect(button('Build improved CV (1)').disabled).toBe(false);

    button('Build improved CV (1)').click();
    fixture.detectChanges();

    const request = http.expectOne('/api/cv/improvement');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ skills: ['Kubernetes'] });
    request.flush(improvement('REWRITING', { skills: ['Kubernetes'] }));
    fixture.detectChanges();

    expect(button('Rewriting the CV…').disabled).toBe(true);
  });

  it('disables the build and explains why when no CV is active', () => {
    flushInitial([], [cluster('c1')]);
    (fixture.nativeElement.querySelector('input[type="checkbox"]') as HTMLInputElement).click();
    fixture.detectChanges();
    expect(button('Build improved CV (1)').disabled).toBe(false);

    TestBed.inject(CvContextStore).reloadCv();
    http.expectOne('/api/cv').flush('no cv', { status: 404, statusText: 'Not Found' });
    fixture.detectChanges();

    expect(button('Build improved CV (1)').disabled).toBe(true);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Upload a CV to continue.');
  });

  it('shows the download link once a build completes', () => {
    flushInitial([], [cluster('c1')]);

    TestBed.inject(CvImprovementStore).onUpdated(
      improvement('COMPLETED', { resultUrl: '/api/cv/improvement/result?v=run1' }),
    );
    fixture.detectChanges();

    const link = fixture.nativeElement.querySelector(
      'a[download]',
    ) as HTMLAnchorElement | null;
    expect(link?.getAttribute('href')).toBe('/api/cv/improvement/result?v=run1');
    expect(link?.textContent).toContain('Download PDF');
  });

  it('renames a grouped skill from the pencil', () => {
    flushInitial([], [cluster('c1', { label: 'Docker & Kubernetes' })]);

    const edit = fixture.nativeElement.querySelector(
      'button[aria-label="Rename Docker & Kubernetes"]',
    ) as HTMLButtonElement;
    edit.click();
    fixture.detectChanges();

    const input = fixture.nativeElement.querySelector(
      'app-inline-title-edit input',
    ) as HTMLInputElement;
    expect(input).toBeTruthy();
    input.value = 'Kubernetes';
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    fixture.detectChanges();

    const request = http.expectOne('/api/skill-gaps/clusters/c1');
    expect(request.request.method).toBe('PATCH');
    expect(request.request.body).toEqual({ label: 'Kubernetes' });
    request.flush(clusterState([cluster('c1', { label: 'Kubernetes' })]));
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Kubernetes');
  });

  it('removes a grouped skill and reloads the curated list', () => {
    flushInitial([gap('g1')], [cluster('c1', { label: 'Kubernetes' }), cluster('c2')]);

    const remove = fixture.nativeElement.querySelector(
      'button[aria-label="Remove Kubernetes"]',
    ) as HTMLButtonElement;
    remove.click();
    fixture.detectChanges();

    const request = http.expectOne('/api/skill-gaps/clusters/c1');
    expect(request.request.method).toBe('DELETE');
    request.flush(null, { status: 204, statusText: 'No Content' });

    http.expectOne('/api/skill-gaps').flush([gap('g1')]);
    http.expectOne('/api/skill-gaps/clusters').flush(clusterState([cluster('c2')]));
    fixture.detectChanges();

    const content = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(content).not.toContain('Kubernetes');
  });
});
