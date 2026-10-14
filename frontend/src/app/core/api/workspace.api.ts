import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { catchError, Observable, of, throwError } from 'rxjs';
import { AnalysisDetail, AnalysisSummary, CvInfo, ModelState } from '../models';

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
      .pipe(catchError((err: unknown) => (isNotFound(err) ? of(null) : throwError(() => err as Error))));
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

  renameAnalysis(id: string, title: string): Observable<AnalysisSummary> {
    return this.http.patch<AnalysisSummary>(`/api/analyses/${encodeURIComponent(id)}`, { title });
  }

  applyAnalysis(id: string, approvedSuggestionIds: string[]): Observable<AnalysisSummary> {
    return this.http.post<AnalysisSummary>(`/api/analyses/${encodeURIComponent(id)}/apply`, {
      approvedSuggestionIds,
    });
  }

  retryAnalysis(id: string): Observable<AnalysisSummary> {
    return this.http.post<AnalysisSummary>(`/api/analyses/${encodeURIComponent(id)}/retry`, {});
  }

  deleteAnalysis(id: string): Observable<void> {
    return this.http.delete<void>(`/api/analyses/${encodeURIComponent(id)}`);
  }

  /** Result download URL (docs/API.md §4). */
  resultUrl(id: string): string {
    return `/api/analyses/${encodeURIComponent(id)}/result`;
  }
}

function isNotFound(err: unknown): boolean {
  return err instanceof HttpErrorResponse && err.status === 404;
}
