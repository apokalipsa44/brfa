import { Injectable } from '@angular/core';
import { JobReport, Stage } from '../job.model';

export interface StatusPayload {
  jobId: string;
  stage: Stage;
  message: string;
  progress: number;
}

export interface CompletedPayload {
  jobId: string;
  stage: 'COMPLETED';
  progress: number;
  resultUrl: string;
  report: JobReport | null;
}

export interface FailedPayload {
  jobId: string;
  stage: 'FAILED';
  errorCode: string;
  message: string;
}

export interface JobEventCallbacks {
  onStatus: (data: StatusPayload) => void;
  onCompleted: (data: CompletedPayload) => void;
  onFailed: (data: FailedPayload) => void;
  /** Called on EventSource open/error; the browser reconnects by itself (plan §8.5). */
  onConnectionIssue: (reconnecting: boolean) => void;
}

/**
 * SSE client (plan §8.5): native EventSource per job, named-event listeners,
 * always closed after a terminal event (otherwise it would reconnect forever).
 */
@Injectable({ providedIn: 'root' })
export class JobEventsService {
  private readonly sources = new Map<string, EventSource>();

  watch(jobId: string, callbacks: JobEventCallbacks): void {
    this.unwatch(jobId);
    const source = new EventSource(`/api/cv-jobs/${encodeURIComponent(jobId)}/events`);
    this.sources.set(jobId, source);

    source.addEventListener('status', (event) => {
      const data = parsePayload<StatusPayload>(event as MessageEvent);
      if (data) callbacks.onStatus(data);
    });
    source.addEventListener('completed', (event) => {
      const data = parsePayload<CompletedPayload>(event as MessageEvent);
      this.unwatch(jobId);
      callbacks.onConnectionIssue(false);
      if (data) callbacks.onCompleted(data);
    });
    source.addEventListener('failed', (event) => {
      const data = parsePayload<FailedPayload>(event as MessageEvent);
      this.unwatch(jobId);
      callbacks.onConnectionIssue(false);
      if (data) callbacks.onFailed(data);
    });
    source.onopen = () => callbacks.onConnectionIssue(false);
    source.onerror = () => {
      // Only flag reconnecting while the stream is still ours (not after terminal close).
      if (this.sources.has(jobId)) callbacks.onConnectionIssue(true);
    };
  }

  unwatch(jobId: string): void {
    const source = this.sources.get(jobId);
    if (source) {
      this.sources.delete(jobId);
      source.close();
    }
  }
}

function parsePayload<T>(event: MessageEvent): T | null {
  try {
    return JSON.parse(event.data) as T;
  } catch {
    return null;
  }
}
