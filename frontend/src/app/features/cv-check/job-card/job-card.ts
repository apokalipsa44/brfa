import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';
import { Job, STAGE_LABELS } from '../../../core/job.model';
import { JobsStore } from '../../../core/jobs.store';

/**
 * One job card (plan §8.4): small per-job progress indicator, never a
 * full-screen loader. Shows the completion report once the real LLM
 * pipeline (M7+) attaches it; the simulated pipeline only drives stages.
 */
@Component({
  selector: 'app-job-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <article class="rounded-lg border border-gray-200 bg-white p-4 shadow-sm">
      <div class="flex items-start justify-between gap-2">
        <div class="min-w-0">
          <h3 class="truncate text-sm font-semibold" [title]="job().fileName">{{ job().fileName }}</h3>
          <p class="mt-0.5 font-mono text-xs text-gray-400">{{ job().jobId }}</p>
        </div>
        <span
          class="shrink-0 rounded-full px-2 py-0.5 text-xs font-medium"
          [class.bg-blue-100]="job().stage !== 'COMPLETED' && job().stage !== 'FAILED'"
          [class.text-blue-800]="job().stage !== 'COMPLETED' && job().stage !== 'FAILED'"
          [class.bg-green-100]="job().stage === 'COMPLETED'"
          [class.text-green-800]="job().stage === 'COMPLETED'"
          [class.bg-red-100]="job().stage === 'FAILED'"
          [class.text-red-800]="job().stage === 'FAILED'"
        >
          {{ stageLabel() }}
        </span>
      </div>

      <div class="mt-2 h-2 overflow-hidden rounded-full bg-gray-100" role="progressbar"
        [attr.aria-valuenow]="job().progress" aria-valuemin="0" aria-valuemax="100" [attr.aria-label]="stageLabel()">
        <div class="h-full rounded-full bg-blue-600 transition-all" [style.width.%]="job().progress"></div>
      </div>
      <p class="mt-1 text-xs text-gray-600">{{ job().message }}</p>

      @if (job().reconnecting && job().stage !== 'COMPLETED' && job().stage !== 'FAILED') {
        <p class="mt-1 text-xs text-amber-600">Reconnecting… live updates will resume automatically.</p>
      }

      @if (job().stage === 'COMPLETED') {
        <div class="mt-2 rounded-md bg-green-50 p-2 text-sm text-green-800">
          @if (job().report !== null && job().report !== undefined) {
            <p class="font-medium">Analysis report:</p>
            <pre class="mt-1 max-h-40 overflow-auto text-xs">{{ reportText() }}</pre>
          } @else {
            <p>Done (demo pipeline — match score and changes list arrive with the real LLM steps).</p>
          }
        </div>
      }

      @if (job().stage === 'FAILED') {
        <p role="alert" class="mt-2 rounded-md bg-red-50 p-2 text-sm text-red-700">
          {{ job().errorMessage ?? 'Processing failed.' }}
          @if (job().errorCode) {
            <span class="font-mono text-xs">({{ job().errorCode }})</span>
          }
        </p>
        <button
          type="button"
          class="mt-2 rounded-md border border-gray-300 px-3 py-1 text-sm hover:bg-gray-50"
          (click)="retry()"
        >
          Dismiss &amp; try again
        </button>
      } @else {
        <button
          type="button"
          class="mt-2 text-xs text-gray-400 hover:text-gray-600 hover:underline"
          (click)="dismiss()"
        >
          Dismiss
        </button>
      }
    </article>
  `,
})
export class JobCard {
  readonly job = input.required<Job>();
  private readonly store = inject(JobsStore);

  stageLabel(): string {
    return STAGE_LABELS[this.job().stage];
  }

  reportText(): string {
    try {
      return JSON.stringify(this.job().report, null, 2);
    } catch {
      return String(this.job().report);
    }
  }

  dismiss(): void {
    this.store.remove(this.job().jobId);
  }

  retry(): void {
    this.store.remove(this.job().jobId);
  }
}
