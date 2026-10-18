import { Component, viewChild } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { SkillGaps } from './skill-gaps';
import { GapItem } from '../../core/models';

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

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Host],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Host);
  });

  afterEach(() => http.verify());

  it('groups gaps by status and drops empty groups', () => {
    http.expectOne('/api/skill-gaps').flush([
      gap('g1', { text: 'Kubernetes', status: 'OPEN', kind: 'MISSING' }),
      gap('g2', { text: 'GraphQL', status: 'LEARNED', kind: 'NICE' }),
      gap('g3', { text: 'Terraform', status: 'OPEN', kind: 'NICE' }),
    ]);
    fixture.detectChanges();

    expect(sectionTitles()).toEqual(['Open', 'Learned']); // no Planned section
    const openItems = fixture.nativeElement.querySelectorAll('section:first-of-type li');
    expect(openItems.length).toBe(2);
    // Missing terms come before nice-to-haves inside a group (text is an input value).
    const firstText = (openItems[0].querySelector('input[type="text"]') as HTMLInputElement).value;
    expect(firstText).toContain('Kubernetes');
  });

  it('collapses and expands a group', () => {
    http.expectOne('/api/skill-gaps').flush([gap('g1', { status: 'OPEN' })]);
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

  it('filters the grouped list by kind', () => {
    http.expectOne('/api/skill-gaps').flush([
      gap('g1', { text: 'Kubernetes', kind: 'MISSING' }),
      gap('g2', { text: 'GraphQL', kind: 'NICE' }),
    ]);
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
});
