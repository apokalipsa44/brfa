import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { JobsStore } from '../../core/jobs.store';
import { UploadForm } from './upload-form/upload-form';
import { JobList } from './job-list/job-list';

/** Page: form + job list (plan §8.3 features/cv-check/cv-check.page.ts). */
@Component({
  selector: 'app-cv-check-page',
  imports: [UploadForm, JobList],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mx-auto flex max-w-2xl flex-col gap-4 p-4">
      <header>
        <h1 class="text-2xl font-bold">CV Checker</h1>
        <p class="text-sm text-gray-600">
          ATS-friendly CV improvements.
          @if (store.activeCount() > 0) {
            <span class="font-medium">{{ store.activeCount() }} job(s) running…</span>
          }
        </p>
      </header>

      @if (store.toast(); as message) {
        <div role="status" class="flex items-center justify-between rounded-md bg-green-600 px-3 py-2 text-sm text-white">
          <span>{{ message }}</span>
          <button type="button" class="ml-3 underline" (click)="store.dismissToast()">Dismiss</button>
        </div>
      }

      <app-upload-form />
      <app-job-list [jobs]="store.jobs()" />
    </div>
  `,
})
export class CvCheckPage {
  readonly store = inject(JobsStore);
}
