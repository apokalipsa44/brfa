import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Component, signal } from '@angular/core';

import { AnalysisCard } from './analysis-card';
import { AnalysisSummary } from '../../core/models';

function makeSummary(overrides: Partial<AnalysisSummary> = {}): AnalysisSummary {
  return {
    id: 'a1',
    title: 'Acme · Senior Java Developer',
    stage: 'AWAITING_REVIEW',
    score: 58,
    shortDescription: 'A backend role using Java and Kubernetes.',
    appliedAt: null,
    progress: 40,
    createdAt: '2026-10-12T10:00:00Z',
    updatedAt: '2026-10-12T10:05:00Z',
    errorCode: null,
    errorMessage: null,
    ...overrides,
  };
}

@Component({
  imports: [AnalysisCard],
  template: `<app-analysis-card
    [summary]="summary()"
    [isSelected]="false"
    (selected)="selected.set($event)"
    (renamed)="renamed.set($event)"
    (appliedChanged)="applied.set($event)"
    (deleted)="deleted.set($event)"
    (cancelled)="cancelled.set($event)"
  />`,
})
class Host {
  readonly summary = signal(makeSummary());
  readonly selected = signal<string | null>(null);
  readonly renamed = signal<{ id: string; title: string } | null>(null);
  readonly applied = signal<{ id: string; applied: boolean } | null>(null);
  readonly deleted = signal<string | null>(null);
  readonly cancelled = signal<string | null>(null);
}

describe('AnalysisCard', () => {
  let fixture: ComponentFixture<Host>;
  let host: InstanceType<typeof Host>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [Host] }).compileComponents();
    fixture = TestBed.createComponent(Host);
    host = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('shows the short description, score label and status', () => {
    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('A backend role using Java and Kubernetes.');
    expect(text).toContain('Needs review');
    expect(
      fixture.nativeElement.querySelector('[aria-label="Match score 58 out of 100"]'),
    ).toBeTruthy();
  });

  it('shows when the analysis was updated as a date with time', () => {
    const text = fixture.nativeElement.textContent as string;
    expect(text).toMatch(/\d{1,2} [A-Z][a-z]{2} \d{4}, \d{2}:\d{2}/);
  });

  it('shows the applied marker only on review and completed analyses', () => {
    expect(fixture.nativeElement.querySelector('button[aria-pressed]')).toBeTruthy();

    host.summary.set(makeSummary({ stage: 'FAILED' }));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('button[aria-pressed]')).toBeNull();
  });

  it('treats a missing applied date as not applied', () => {
    host.summary.set(makeSummary({ appliedAt: undefined as unknown as null }));
    fixture.detectChanges();

    const marker = fixture.nativeElement.querySelector('button[aria-pressed]') as HTMLButtonElement;
    expect(marker.getAttribute('aria-pressed')).toBe('false');
  });

  it('emits an applied change when the marker is clicked', () => {
    const marker = fixture.nativeElement.querySelector('button[aria-pressed]') as HTMLButtonElement;
    marker.click();
    expect(host.applied()).toEqual({ id: 'a1', applied: true });
  });

  it('renames inline from the pencil', () => {
    const rename = fixture.nativeElement.querySelector(
      'button[aria-label="Rename analysis"]',
    ) as HTMLButtonElement;
    rename.click();
    fixture.detectChanges();

    const input = fixture.nativeElement.querySelector('input[type="text"]') as HTMLInputElement;
    expect(input).toBeTruthy();
    input.value = 'Interview at Globex';
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    fixture.detectChanges();

    expect(host.renamed()).toEqual({ id: 'a1', title: 'Interview at Globex' });
  });

  it('deletes directly from the card, with no options menu', () => {
    expect(
      fixture.nativeElement.querySelector('button[aria-label="Actions for analysis"]'),
    ).toBeNull();

    const remove = fixture.nativeElement.querySelector(
      'button[aria-label="Delete analysis"]',
    ) as HTMLButtonElement;
    expect(remove).toBeTruthy();
    remove.click();

    expect(host.deleted()).toBe('a1');
  });

  it('renders a compact row (no score or description) while working', () => {
    host.summary.set(makeSummary({ stage: 'EVALUATING', score: null, shortDescription: null }));
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.animate-spin')).toBeTruthy();
    expect(fixture.nativeElement.textContent).not.toContain('A backend role');
    expect(fixture.nativeElement.querySelector('button[aria-pressed]')).toBeNull();
  });

  it('offers a stop button on a working analysis that emits its id', () => {
    host.summary.set(makeSummary({ stage: 'EVALUATING', score: null, shortDescription: null }));
    fixture.detectChanges();

    const stop = fixture.nativeElement.querySelector(
      'button[aria-label="Cancel analysis"]',
    ) as HTMLButtonElement;
    expect(stop).toBeTruthy();
    stop.click();

    expect(host.cancelled()).toBe('a1');
  });
});
