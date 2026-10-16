import { DestroyRef, inject, Injectable, signal } from '@angular/core';

import { AnalysesStore } from '../analyses.store';
import { CvContextStore } from '../cv-context.store';
import { AnalysisSummary } from '../models';

interface DeletedPayload {
  analysisId: string;
}

interface OutputPayload {
  analysisId: string;
  stage: string;
  delta: string;
}

/**
 * The single SSE stream for the whole app (docs/API.md §7). One `EventSource('/api/events')`
 * receives events for every analysis, which removes the per-domain connection limit that used to
 * strand cards on "Reconnecting…". The browser reconnects by itself and replays missed events via
 * `Last-Event-ID`, so nothing here opens or closes per analysis.
 */
@Injectable({ providedIn: 'root' })
export class EventsService {
  private readonly analyses = inject(AnalysesStore);
  private readonly cv = inject(CvContextStore);

  private _disconnected = signal(false);

  /** True only once the connection has been down for more than a few seconds. */
  readonly disconnected = this._disconnected.asReadonly();

  private source: EventSource | null = null;
  private notifyTimer = 0;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.disconnect());
  }

  /** Opens the stream. Idempotent: repeated calls keep the single connection. */
  connect(): void {
    if (this.source) return;
    const source = new EventSource('/api/events');
    this.source = source;

    source.addEventListener('open', () => {
      if (this.notifyTimer) {
        clearTimeout(this.notifyTimer);
        this.notifyTimer = 0;
      }
      this._disconnected.set(false);
    });

    source.addEventListener('analysis.updated', (event) => {
      const summary = parse<AnalysisSummary>(event);
      if (summary?.id) this.analyses.onAnalysisUpdated(summary);
    });

    source.addEventListener('analysis.deleted', (event) => {
      const payload = parse<DeletedPayload>(event);
      if (payload?.analysisId) this.analyses.onAnalysisDeleted(payload.analysisId);
    });

    // Transient live LLM output: shown while a stage runs, never replayed after a reconnect.
    source.addEventListener('analysis.output', (event) => {
      const payload = parse<OutputPayload>(event);
      if (payload?.analysisId && payload.delta) {
        this.analyses.onAnalysisOutput(payload.analysisId, payload.delta);
      }
    });

    // A `models.updated` event carries no state, so the client re-fetches the model context.
    source.addEventListener('models.updated', () => this.cv.refreshModels());

    // A `cv.updated` event carries no state, so the client re-fetches the active CV (another tab replaced it).
    source.addEventListener('cv.updated', () => this.cv.reloadCv());

    // Heartbeat: a liveness signal only, never shown to the user.
    source.addEventListener('ping', () => {
      this._disconnected.set(false);
    });

    source.addEventListener('error', () => {
      // The browser retries on its own; only the connection indicator becomes visible.
      if (!this.notifyTimer) {
        this.notifyTimer = window.setTimeout(() => this._disconnected.set(true), 4000);
      }
    });
  }

  disconnect(): void {
    if (this.notifyTimer) {
      clearTimeout(this.notifyTimer);
      this.notifyTimer = 0;
    }
    this.source?.close();
    this.source = null;
    this._disconnected.set(false);
  }
}

function parse<T>(event: Event): T | null {
  try {
    return JSON.parse((event as MessageEvent<string>).data) as T;
  } catch {
    return null;
  }
}
