import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { JobReport, Stage } from '../job.model';

/** Response of POST /api/cv (docs/API.md §7): the persistent CV reused by every job. */
export interface StoredCv {
  cvId: string;
  fileName: string;
  sizeBytes: number;
  createdAt?: string;
}

/** Response of POST /api/cv-jobs (docs/API.md): 202, processing runs asynchronously. */
export interface CreatedJobResponse {
  jobId: string;
  stage: Stage;
}

/** One entry of GET /api/cv-jobs (docs/API.md §8): the persisted recent job history. */
export interface JobSnapshot {
  jobId: string;
  fileName: string;
  stage: Stage;
  createdAt: string;
  progress: number;
  resultUrl?: string | null;
  report?: JobReport | null;
  errorCode?: string | null;
  errorMessage?: string | null;
  model?: string | null;
}

/** One entry of GET /api/llm/models (docs/API.md §6). */
export interface ModelEntry {
  id: string;
  loaded: boolean;
}

/** Response of GET /api/llm/models (docs/API.md §6): all models LM Studio can serve. */
export interface ModelsResponse {
  models: ModelEntry[];
  activeModelId: string | null;
  ready: boolean;
  reason?: string | null;
}

/** Response of GET /api/llm/models/loaded (docs/API.md §6.1): what is loaded in LM Studio now. */
export interface LoadedModelsInfo {
  reachable: boolean;
  loaded: string[];
}

/** Thin HTTP wrapper (plan §8.3 core/api). SSE lives in JobEventsService, not here. */
@Injectable({ providedIn: 'root' })
export class CvJobsApi {
  private readonly http = inject(HttpClient);

  /** Uploads the CV once; it is stored server-side and reused for every job (docs/API.md §7). */
  uploadCv(file: File): Observable<StoredCv> {
    const form = new FormData();
    form.append('file', file, file.name);
    return this.http.post<StoredCv>('/api/cv', form);
  }

  /** Metadata of the stored CV; 404 when it was replaced or the backend restarted. */
  getCv(cvId: string): Observable<StoredCv> {
    return this.http.get<StoredCv>(`/api/cv/${encodeURIComponent(cvId)}`);
  }

  /**
   * Creates a job from the stored CV (docs/API.md §1). Neither the PDF nor the model is part of
   * the request — the CV was uploaded via {@link #uploadCv} and the LM Studio model switched via
   * {@link #loadModel}, both independently of any job.
   */
  createJob(cvId: string, jobDescription: string): Observable<CreatedJobResponse> {
    const form = new FormData();
    form.append('cvId', cvId);
    form.append('jobDescription', jobDescription);
    return this.http.post<CreatedJobResponse>('/api/cv-jobs', form);
  }

  /** Recent jobs persisted by the backend, newest first (docs/API.md §8). */
  listJobs(): Observable<JobSnapshot[]> {
    return this.http.get<JobSnapshot[]>('/api/cv-jobs');
  }

  /** Removes a job from the backend history — the Dismiss action (docs/API.md §8). */
  deleteJob(jobId: string): Observable<void> {
    return this.http.delete<void>(`/api/cv-jobs/${encodeURIComponent(jobId)}`);
  }

  /** Models LM Studio can serve (GET /api/llm/models) — ids for the picker in the upload form. */
  getAvailableModels(): Observable<string[]> {
    return this.http
      .get<ModelsResponse>('/api/llm/models')
      .pipe(map((response) => response.models.map((model) => model.id)));
  }

  /** What is loaded in LM Studio right now (docs/API.md §6.1) — drives the status line and Submit. */
  getLoadedModels(): Observable<LoadedModelsInfo> {
    return this.http.get<LoadedModelsInfo>('/api/llm/models/loaded');
  }

  /** Loads the given model in LM Studio, making it the active model (docs/API.md §6). */
  loadModel(model: string): Observable<void> {
    return this.http.post<void>('/api/llm/models/load', { model });
  }

  resultUrl(jobId: string): string {
    return `/api/cv-jobs/${encodeURIComponent(jobId)}/result`;
  }
}
