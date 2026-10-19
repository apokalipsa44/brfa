import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { catchError, Observable, of, throwError } from 'rxjs';
import { AnalysisDetail, AnalysisSummary, CvImprovementState, CvInfo, GapItem, GapKind, GapStatus, ModelState, SkillClusters } from '../models';

/** The optional fields of a PATCH /api/skill-gaps/{id} (docs/API.md §9); at least one is sent. */
export interface GapPatch {
  text?: string;
  kind?: GapKind;
  status?: GapStatus;
  note?: string;
}

/** The optional fields of a PATCH /api/analyses/{id} (docs/API.md §4); at least one is sent. */
export interface AnalysisPatch {
  title?: string;
  description?: string;
  applied?: boolean;
  appliedAt?: string;
}

/**
 * Thin HTTP wrapper for the workspace API (docs/API.md). SSE is not handled here — the single global
 * stream lives in `EventsService`. The backend owns the active CV and model, so these calls only
 * exchange metadata and the posting text.
 */
@Injectable({ providedIn: 'root' })
export class WorkspaceApi {
  private readonly http = inject(HttpClient);

  /** Uploads or replaces the active CV (docs/API.md §2). Text extraction errors surface as 422. */
  uploadCv(file: File): Observable<CvInfo> {
    const form = new FormData();
    form.append('file', file, file.name);
    return this.http.post<CvInfo>('/api/cv', form);
  }

  /** The active CV, or null when none exists (404 is the normal "no CV" answer, not an error). */
  getCv(): Observable<CvInfo | null> {
    return this.http
      .get<CvInfo>('/api/cv')
      .pipe(
        catchError((err: unknown) => (isNotFound(err) ? of(null) : throwError(() => err as Error))),
      );
  }

  /** Model candidates, the active selection and readiness (docs/API.md §3). */
  getModels(): Observable<ModelState> {
    return this.http.get<ModelState>('/api/llm/models');
  }

  /** Selects and loads the active model (docs/API.md §3); the returned state may still be unready. */
  setActiveModel(modelId: string): Observable<ModelState> {
    return this.http.put<ModelState>('/api/llm/active-model', { modelId });
  }

  listAnalyses(): Observable<AnalysisSummary[]> {
    return this.http.get<AnalysisSummary[]>('/api/analyses');
  }

  getAnalysis(id: string): Observable<AnalysisDetail> {
    return this.http.get<AnalysisDetail>(`/api/analyses/${encodeURIComponent(id)}`);
  }

  createAnalysis(jobPosting: string): Observable<AnalysisSummary> {
    return this.http.post<AnalysisSummary>('/api/analyses', { jobPosting });
  }

  /** Starts an analysis from a link; the server fetches the posting (docs/API.md §4). */
  createAnalysisFromUrl(jobPostingUrl: string): Observable<AnalysisSummary> {
    return this.http.post<AnalysisSummary>('/api/analyses', { jobPostingUrl });
  }

  /** Partial update of the user title, description and/or the applied marker (docs/API.md §4). */
  updateAnalysis(id: string, patch: AnalysisPatch): Observable<AnalysisSummary> {
    return this.http.patch<AnalysisSummary>(`/api/analyses/${encodeURIComponent(id)}`, patch);
  }

  applyAnalysis(id: string, approvedSuggestionIds: string[]): Observable<AnalysisSummary> {
    return this.http.post<AnalysisSummary>(`/api/analyses/${encodeURIComponent(id)}/apply`, {
      approvedSuggestionIds,
    });
  }

  retryAnalysis(id: string): Observable<AnalysisSummary> {
    return this.http.post<AnalysisSummary>(`/api/analyses/${encodeURIComponent(id)}/retry`, {});
  }

  /** Re-runs a link analysis from scratch, overwriting its results (docs/API.md §4). */
  rerunAnalysis(id: string): Observable<AnalysisSummary> {
    return this.http.post<AnalysisSummary>(`/api/analyses/${encodeURIComponent(id)}/rerun`, {});
  }

  /** Stops a queued or running analysis (docs/API.md §4); the worker's LLM call is aborted. */
  cancelAnalysis(id: string): Observable<AnalysisSummary> {
    return this.http.post<AnalysisSummary>(`/api/analyses/${encodeURIComponent(id)}/cancel`, {});
  }

  deleteAnalysis(id: string): Observable<void> {
    return this.http.delete<void>(`/api/analyses/${encodeURIComponent(id)}`);
  }

  /** Aggregated + curated skill gaps (docs/API.md §9); the backend re-syncs from analyses on read. */
  listGaps(): Observable<GapItem[]> {
    return this.http.get<GapItem[]>('/api/skill-gaps');
  }

  /** Adds a user-created skill gap (docs/API.md §9). */
  createGap(text: string, kind: GapKind, note: string): Observable<GapItem> {
    return this.http.post<GapItem>('/api/skill-gaps', { text, kind, note });
  }

  /** Partial update of a skill gap's text, kind, status or note (docs/API.md §9). */
  updateGap(id: string, patch: GapPatch): Observable<GapItem> {
    return this.http.patch<GapItem>(`/api/skill-gaps/${encodeURIComponent(id)}`, patch);
  }

  /** Removes a skill gap (docs/API.md §9). */
  deleteGap(id: string): Observable<void> {
    return this.http.delete<void>(`/api/skill-gaps/${encodeURIComponent(id)}`);
  }

  /** Grouped + ranked skills plus the regrouping job's state (docs/API.md §9). */
  listSkillClusters(): Observable<SkillClusters> {
    return this.http.get<SkillClusters>('/api/skill-gaps/clusters');
  }

  /** Runs the regrouping synchronously and returns the refreshed payload (docs/API.md §9). */
  rebuildSkillClusters(): Observable<SkillClusters> {
    return this.http.post<SkillClusters>('/api/skill-gaps/clusters/rebuild', {});
  }

  /** Renames one grouped skill's label (docs/API.md §9); returns the refreshed payload. */
  renameSkillCluster(id: string, label: string): Observable<SkillClusters> {
    return this.http.patch<SkillClusters>(`/api/skill-gaps/clusters/${encodeURIComponent(id)}`, {
      label,
    });
  }

  /** Removes a grouped skill and every underlying gap it grouped (docs/API.md §9). */
  deleteSkillCluster(id: string): Observable<void> {
    return this.http.delete<void>(`/api/skill-gaps/clusters/${encodeURIComponent(id)}`);
  }

  /** Current standalone CV build state (docs/API.md §10); `NONE` until the first build. */
  getCvImprovement(): Observable<CvImprovementState> {
    return this.http.get<CvImprovementState>('/api/cv/improvement');
  }

  /** Starts a standalone CV build that adds the selected skills (docs/API.md §10). */
  startCvImprovement(skills: string[]): Observable<CvImprovementState> {
    return this.http.post<CvImprovementState>('/api/cv/improvement', { skills });
  }
}

function isNotFound(err: unknown): boolean {
  return err instanceof HttpErrorResponse && err.status === 404;
}
