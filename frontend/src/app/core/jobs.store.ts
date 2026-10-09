import { computed, inject, Injectable, signal } from '@angular/core';
import { Job, Stage } from './job.model';
import { CompletedPayload, FailedPayload, JobEventsService, StatusPayload } from './sse/job-events.service';

interface StoredJobRef {
  jobId: string;
  fileName: string;
}

const STORAGE_KEY = 'cvchecker.jobs.v1';

/**
 * Signal-based job registry (plan §8.3 core/jobs.store.ts): the single source
 * of state for the job list. Updated live from SSE; never blocks the UI —
 * uploads return immediately and each card tracks its own progress (plan §8.4).
 */
@Injectable({ providedIn: 'root' })
export class JobsStore {
  private readonly events = inject(JobEventsService);

  private readonly _jobs = signal<Job[]>([]);
  private readonly _toast = signal<string | null>(null);

  readonly jobs = this._jobs.asReadonly();
  readonly toast = this._toast.asReadonly();
  readonly activeCount = computed(() => this._jobs().filter((job) => !isDone(job)).length);

  constructor() {
    this.resumeStored();
  }

  /** Registers a freshly created (202) job and opens its SSE stream. */
  track(jobId: string, fileName: string): void {
    if (this._jobs().some((job) => job.jobId === jobId)) return;
    this._jobs.update((jobs) => [
      { jobId, fileName, stage: 'QUEUED', message: 'Queued for processing', progress: 0, reconnecting: false },
      ...jobs,
    ]);
    this.persist();
    this.watch(jobId);
  }

  remove(jobId: string): void {
    this.events.unwatch(jobId);
    this._jobs.update((jobs) => jobs.filter((job) => job.jobId !== jobId));
    this.persist();
  }

  dismissToast(): void {
    this._toast.set(null);
  }

  /** Re-opens SSE streams for jobs stored before a page refresh (plan §8.5). */
  private resumeStored(): void {
    for (const ref of readStored()) {
      if (this._jobs().some((job) => job.jobId === ref.jobId)) continue;
      this._jobs.update((jobs) => [
        ...jobs,
        {
          jobId: ref.jobId,
          fileName: ref.fileName,
          stage: 'QUEUED' as Stage,
          message: 'Resuming…',
          progress: 0,
          reconnecting: false,
        },
      ]);
      this.watch(ref.jobId);
    }
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

  private persist(): void {
    try {
      const refs: StoredJobRef[] = this._jobs().map((job) => ({ jobId: job.jobId, fileName: job.fileName }));
      localStorage.setItem(STORAGE_KEY, JSON.stringify(refs));
    } catch {
      // Storage full or unavailable (private mode): live updates still work for this session.
    }
  }
}

function isDone(job: Job): boolean {
  return job.stage === 'COMPLETED' || job.stage === 'FAILED';
}

function readStored(): StoredJobRef[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return [];
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed.filter(
      (entry): entry is StoredJobRef =>
        typeof entry === 'object' && entry !== null && typeof (entry as StoredJobRef).jobId === 'string',
    );
  } catch {
    return [];
  }
}
