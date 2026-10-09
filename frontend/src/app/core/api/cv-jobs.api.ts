import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Stage } from '../job.model';

/** Response of POST /api/cv-jobs (docs/API.md): 202, processing runs asynchronously. */
export interface CreatedJobResponse {
  jobId: string;
  stage: Stage;
}

/** Thin HTTP wrapper (plan §8.3 core/api). SSE lives in JobEventsService, not here. */
@Injectable({ providedIn: 'root' })
export class CvJobsApi {
  private readonly http = inject(HttpClient);

  createJob(file: File, jobDescription: string): Observable<CreatedJobResponse> {
    const form = new FormData();
    form.append('file', file, file.name);
    form.append('jobDescription', jobDescription);
    return this.http.post<CreatedJobResponse>('/api/cv-jobs', form);
  }

  resultUrl(jobId: string): string {
    return `/api/cv-jobs/${encodeURIComponent(jobId)}/result`;
  }
}
