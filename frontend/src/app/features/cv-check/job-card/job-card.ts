import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';
import { Job, STAGE_LABELS } from '../../../core/job.model';
import { JobsStore } from '../../../core/jobs.store';

/**
 * One job card (plan §8.4): small per-job progress indicator, never a full-screen loader.
 * After COMPLETED it shows the match score, keywords, summary, the change list and the
 * Download improved CV button; after FAILED an understandable message.
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
          [class]="badgeClasses()"
        >
          {{ stageLabel() }}
        </span>
      </div>

      <div class="mt-2 h-2 overflow-hidden rounded-full bg-gray-100" role="progressbar"
        [attr.aria-valuenow]="job().progress" aria-valuemin="0" aria-valuemax="100" [attr.aria-label]="stageLabel()">
        <div class="h-full rounded-full bg-blue-600 transition-all" [style.width.%]="job().progress"></div>
      </div>
      <p class="mt-1 text-xs text-gray-600">{{ job().message }}</p>

      @if (job().reconnecting && !isDone()) {
        <p class="mt-1 text-xs text-amber-600">Reconnecting… live updates will resume automatically.</p>
      }

      @if (job().stage === 'COMPLETED') {
        @if (job().report; as report) {
          <div class="mt-2 rounded-md border border-blue-200 bg-blue-50 p-2 text-sm text-blue-800">
            <p class="text-xs font-medium uppercase">CV Analysis</p>
            <p class="mt-1">
              Match score: <span class="font-semibold">{{ report.matchScoreBefore }}%</span>
            </p>
            @if (report.summary) {
              <p class="mt-1 text-xs text-gray-700">{{ report.summary }}</p>
            }
            @if (report.matchedKeywords.length > 0) {
              <p class="mt-1 text-xs text-blue-700">Matched keywords:</p>
              <ul class="mt-0.5 list-disc pl-4 text-xs text-blue-700">
                @for (kw of report.matchedKeywords; track kw) {
                  <li>{{ kw }}</li>
                }
              </ul>
            }
            @if (report.missingKeywords.length > 0) {
              <p class="mt-1 text-xs text-orange-700">Missing keywords:</p>
              <ul class="mt-0.5 list-disc pl-4 text-xs text-orange-700">
                @for (kw of report.missingKeywords; track kw) {
                  <li>{{ kw }}</li>
                }
              </ul>
            }
          </div>

          @if (report.changes.length > 0) {
            <div class="mt-2">
              <p class="text-xs font-medium uppercase text-gray-600">Changes applied</p>
              <ul class="mt-1 flex flex-col gap-1">
                @for (change of report.changes; track $index) {
                  <li class="rounded-md border border-gray-200 bg-gray-50 p-2 text-xs">
                    <p class="font-medium">{{ change.section }}</p>
                    <p class="mt-0.5 text-gray-500 line-through">{{ change.before }}</p>
                    <p class="mt-0.5 text-gray-800">{{ change.after }}</p>
                    <p class="mt-0.5 text-gray-500">{{ change.reason }}</p>
                  </li>
                }
              </ul>
            </div>
          }
        } @else {
          <p class="mt-2 rounded-md bg-green-50 p-2 text-sm text-green-800">
            Done (demo pipeline — match score and changes list arrive with the real LLM steps).
          </p>
        }

        @if (job().resultUrl) {
          <a
            [href]="job().resultUrl"
            download
            class="mt-2 inline-flex items-center gap-1 rounded-md bg-blue-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-blue-700"
          >
            Download improved CV
          </a>
        }
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
          (click)="dismiss()"
        >
          Dismiss
        </button>
      } @else if (job().stage !== 'COMPLETED') {
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

  isDone(): boolean {
    return this.job().stage === 'COMPLETED' || this.job().stage === 'FAILED';
  }

  badgeClasses(): string {
    const stage = this.job().stage;
    if (stage === 'COMPLETED') return 'bg-green-100 text-green-800';
    if (stage === 'FAILED') return 'bg-red-100 text-red-800';
    return 'bg-blue-100 text-blue-800';
  }

  dismiss(): void {
    this.store.remove(this.job().jobId);
  }
}