import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { Job } from '../../../core/job.model';
import { JobCard } from '../job-card/job-card';

/** Job list (plan §8.3): one small card per job, newest first. */
@Component({
  selector: 'app-job-list',
  imports: [JobCard],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (jobs().length === 0) {
      <p class="rounded-lg border border-dashed border-gray-300 p-6 text-center text-sm text-gray-500">
        No jobs yet. Submit a CV above to start.
      </p>
    } @else {
      <div class="flex flex-col gap-3">
        @for (job of jobs(); track job.jobId) {
          <app-job-card [job]="job" />
        }
      </div>
    }
  `,
})
export class JobList {
  readonly jobs = input.required<Job[]>();
}
