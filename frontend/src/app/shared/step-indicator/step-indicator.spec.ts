import { Component, signal, viewChild } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { AnalysisStage } from '../../core/models';
import { StepIndicator } from './step-indicator';

@Component({
  imports: [StepIndicator],
  template: `
    <app-step-indicator
      [stage]="stage()"
      [viewed]="viewed()"
      [available]="available()"
      (stepSelected)="selected.push($event)"
    />
  `,
})
class Host {
  readonly stage = signal<AnalysisStage>('AWAITING_REVIEW');
  readonly viewed = signal(1);
  readonly available = signal(2);
  readonly selected: number[] = [];
  readonly indicator = viewChild(StepIndicator);
}

describe('StepIndicator', () => {
  let fixture: ComponentFixture<Host>;
  let host: Host;

  function stepButton(index: number): HTMLButtonElement {
    const label = ['Match', 'Review changes', 'Improved CV'][index];
    const button = fixture.nativeElement.querySelector(
      `button[aria-label="Go to step ${index + 1}: ${label}"]`,
    ) as HTMLButtonElement;
    if (!button) throw new Error(`step ${index + 1} button not found`);
    return button;
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [Host] }).compileComponents();
    fixture = TestBed.createComponent(Host);
    host = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('disables every step while no result is available', () => {
    host.available.set(0);
    fixture.detectChanges();

    expect(stepButton(0).disabled).toBe(true);
    expect(stepButton(1).disabled).toBe(true);
    expect(stepButton(2).disabled).toBe(true);
  });

  it('enables only the steps whose result exists', () => {
    host.available.set(2);
    fixture.detectChanges();

    expect(stepButton(0).disabled).toBe(false);
    expect(stepButton(1).disabled).toBe(false);
    expect(stepButton(2).disabled).toBe(true);
  });

  it('emits the index when an available step is clicked', () => {
    host.available.set(3);
    fixture.detectChanges();

    stepButton(2).click();
    fixture.detectChanges();

    expect(host.selected).toEqual([2]);
  });

  it('does not emit for an unavailable step', () => {
    host.available.set(1);
    fixture.detectChanges();

    stepButton(1).click();
    stepButton(2).click();
    fixture.detectChanges();

    expect(host.selected).toEqual([]);
  });

  it('marks the current stage step and the viewed step', () => {
    host.stage.set('AWAITING_REVIEW');
    host.available.set(2);
    host.viewed.set(0);
    fixture.detectChanges();

    expect(stepButton(1).getAttribute('aria-current')).toBe('step');
    expect(stepButton(0).getAttribute('aria-current')).toBeNull();
  });
});
