import { computed, inject, Injectable, signal } from '@angular/core';
import { Job, STAGE_LABELS } from './job.model';
import { CvJobsApi, JobSnapshot } from './api/cv-jobs.api';
import { CompletedPayload, FailedPayload, JobEventsService, StatusPayload } from './sse/job-events.service';

/**
 * Signal-based job registry (plan §8.3 core/jobs.store.ts): the single source of state for the job
 * list. The recent history comes from the backend (docs/API.md §8) so it survives a page refresh
 * *and* a backend restart; live updates arrive over SSE. Never blocks the UI — uploads return
 * immediately and each card tracks its own progress (plan §8.4).
 */
@Injectable({ providedIn: 'root' })
export class JobsStore {
  private readonly events = inject(JobEventsService);
  private readonly api = inject(CvJobsApi);

  private readonly _jobs = signal<Job[]>([]);
  private readonly _toast = signal<string | null>(null);

  readonly jobs = this._jobs.asReadonly();
  readonly toast = this._toast.asReadonly();
  readonly activeCount = computed(() => this._jobs().filter((job) => !isDone(job)).length);

  constructor() {
    this.restoreFromBackend();
  }

  /** Registers a freshly created (202) job and opens its SSE stream. */
  track(jobId: string, fileName: string): void {
    if (this._jobs().some((job) => job.jobId === jobId)) return;
    this._jobs.update((jobs) => [
      { jobId, fileName, stage: 'QUEUED', message: 'Queued for processing', progress: 0, reconnecting: false },
      ...jobs,
    ]);
    this.watch(jobId);
  }

  remove(jobId: string): void {
    this.events.unwatch(jobId);
    this._jobs.update((jobs) => jobs.filter((job) => job.jobId !== jobId));
    this.api.deleteJob(jobId).subscribe({ error: () => { /* already gone server-side */ } });
  }

  dismissToast(): void {
    this._toast.set(null);
  }

  /** Loads the persisted job list from the backend; resumes SSE for jobs still running. */
  private restoreFromBackend(): void {
    this.api.listJobs().subscribe({
      next: (snapshots) => {
        for (const snapshot of snapshots) {
          if (this._jobs().some((job) => job.jobId === snapshot.jobId)) continue;
          const job = toJob(snapshot);
          this._jobs.update((jobs) => [...jobs, job]);
          if (!isDone(job)) this.watch(job.jobId);
        }
      },
      error: () => { /* backend unavailable: history stays empty, uploads still work */ },
    });
  }

  private watch(jobId: string): void {
    this.events.watch(jobId, {
      onStatus: (data: StatusPayload) =>
        this.patch(jobId, { stage: data.stage, message: data.message, progress: data.progress }),
      onCompleted: (data: CompletedPayload) => {
        this.patch(jobId, {
          stage: 'COMPLETED',
          message: 'Improved CV ready',
          progress: data.progress,
          resultUrl: data.resultUrl,
          report: data.report,
          reconnecting: false,
        });
        this._toast.set('Your CV is ready');
      },
      onFailed: (data: FailedPayload) =>
        this.patch(jobId, {
          stage: 'FAILED',
          message: data.message,
          errorCode: data.errorCode,
          errorMessage: data.message,
          reconnecting: false,
        }),
      onConnectionIssue: (reconnecting: boolean) => this.patch(jobId, { reconnecting }),
    });
  }

  private patch(jobId: string, partial: Partial<Job>): void {
    this._jobs.update((jobs) => jobs.map((job) => (job.jobId === jobId ? { ...job, ...partial } : job)));
  }
}

function isDone(job: Job): boolean {
  return job.stage === 'COMPLETED' || job.stage === 'FAILED';
}

/** Maps a backend snapshot to the UI job shape (docs/API.md §8). */
function toJob(snapshot: JobSnapshot): Job {
  return {
    jobId: snapshot.jobId,
    fileName: snapshot.fileName ?? 'CV',
    model: snapshot.model ?? undefined,
    stage: snapshot.stage,
    message: STAGE_LABELS[snapshot.stage] ?? snapshot.stage,
    progress: snapshot.progress ?? 0,
    resultUrl: snapshot.resultUrl ?? undefined,
    report: snapshot.report ?? null,
    errorCode: snapshot.errorCode ?? undefined,
    errorMessage: snapshot.errorMessage ?? undefined,
    reconnecting: false,
  };
}
