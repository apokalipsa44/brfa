# API Contract (M2)

Single origin: the frontend calls everything under `/api/*`. In dev, `frontend/proxy.conf.json` routes `/api/*` → `http://localhost:8080/api/*` with **no path rewrite** — the backend owns the `/api` prefix. No auth: the unguessable UUID `jobId` is the only access protection (plan §6.5).

## Common types

### Stage

```
QUEUED → EXTRACTING_TEXT → ANALYZING → REWRITING → GENERATING_PDF → COMPLETED
                                                                    ↘ FAILED (from any stage)
```

Indicative `progress` per stage: QUEUED 0, EXTRACTING_TEXT 10, ANALYZING 35, REWRITING 65, GENERATING_PDF 90, COMPLETED 100.

### Error format — RFC 9457 `ProblemDetail`

All error responses (REST and pipeline failures surfaced via REST) use:

```json
{
  "type": "https://cvchecker.local/errors/file-too-large",
  "title": "File too large",
  "status": 413,
  "detail": "The PDF is 7.2 MB; the limit is 5 MB.",
  "instance": "/api/cv-jobs",
  "code": "FILE_TOO_LARGE"
}
```

`code` (extension property) is a stable machine-readable identifier: `MISSING_FILE`, `INVALID_PDF`, `FILE_TOO_LARGE`, `MISSING_JOB_DESCRIPTION`, `JOB_DESCRIPTION_TOO_LONG`.

### Report shape (on completion)

```json
{
  "matchScoreBefore": 58,
  "matchedKeywords": ["Java", "Spring"],
  "missingKeywords": ["Kubernetes"],
  "suggestions": [ { "section": "Skills", "problem": "...", "recommendation": "...", "priority": "HIGH" } ],
  "summary": "…",
  "changes": [ { "section": "Experience", "before": "…", "after": "…", "reason": "…" } ]
}
```

`matchScoreBefore` is the LLM #1 score of the **original** CV against the posting (the pipeline does not re-score the improved version). `changes` comes from LLM #2. Priority: `HIGH | MEDIUM | LOW`.

## Endpoints

### 1. `POST /api/cv-jobs` — create a job

Request: `multipart/form-data`

| Field | Type | Rules |
|---|---|---|
| `file` | PDF file | required; ≤ 5 MB; `Content-Type: application/pdf` **and** `%PDF-` magic bytes (header alone is not trusted) |
| `jobDescription` | text | required; non-empty; ≤ 20,000 characters |

Response **202 Accepted** — the request finishes immediately; processing runs asynchronously:

```json
{ "jobId": "3f7c…-uuid", "stage": "QUEUED" }
```

Errors (ProblemDetail):

| Status | `code` | When |
|---|---|---|
| 400 | `MISSING_FILE` | no `file` part |
| 413 | `FILE_TOO_LARGE` | file > 5 MB |
| 415 | `INVALID_PDF` | wrong content type or missing `%PDF-` signature |
| 400 | `MISSING_JOB_DESCRIPTION` | absent/empty |
| 400 | `JOB_DESCRIPTION_TOO_LONG` | > 20,000 chars |

### 2. `GET /api/cv-jobs/{jobId}` — state snapshot

Used to restore UI state after a page refresh. **200**:

```json
{
  "jobId": "3f7c…-uuid",
  "stage": "ANALYZING",
  "createdAt": "2026-10-09T10:00:00Z",
  "progress": 35,
  "resultUrl": "/api/cv-jobs/3f7c…/result",   // only when stage == COMPLETED
  "report": { … },                            // only when stage == COMPLETED
  "errorCode": "PDF_NO_TEXT",                // only when stage == FAILED
  "errorMessage": "No text found in the PDF (it may be a scan)"
}
```

**404** — unknown `jobId` (or job expired by TTL).

### 3. `GET /api/cv-jobs/{jobId}/events` — SSE stream

Headers: `Content-Type: text/event-stream`, `Cache-Control: no-cache`. Connection stays open until the job reaches a terminal state, then the server closes it after sending the replay.

Every event has an increasing per-job numeric `id`, a name (`event:`), and JSON in `data:`.

| Event | Sent when | `data` payload |
|---|---|---|
| `status` | every stage transition (QUEUED…GENERATING_PDF) | `{ "jobId", "stage", "message", "progress" }` |
| `completed` | job finished | `{ "jobId", "stage": "COMPLETED", "progress": 100, "resultUrl", "report" }` |
| `failed` | pipeline error | `{ "jobId", "stage": "FAILED", "errorCode", "message" }` |

Heartbeat: a comment line `:keepalive` every ~15 s while the job is not terminal.

Replay semantics (plan §6.6): on connect, the client first receives buffered events — starting **after** the `Last-Event-ID` header value if present, otherwise from the beginning — and only then live events. Replay and appending happen under one lock per job: no loss, no duplicates. If the job is already terminal at connect time, the server sends the replay (or just the terminal event) and closes immediately.

Example stream:

```
id: 3
event: status
data: {"jobId":"…","stage":"ANALYZING","message":"Analyzing CV match against the job posting","progress":35}

id: 7
event: completed
data: {"jobId":"…","stage":"COMPLETED","progress":100,"resultUrl":"/api/cv-jobs/…/result","report":{"matchScoreBefore":58,"changes":[…]}}
```

**404** — unknown `jobId`.

### 4. `GET /api/cv-jobs/{jobId}/result` — download improved PDF

- **200**: `Content-Type: application/pdf`, `Content-Disposition: attachment; filename="cv-improved-{jobId}.pdf"`.
- **409**: job exists but is not COMPLETED yet (still processing or failed).
- **404**: unknown `jobId` / result no longer stored.

## Pipeline error codes (`failed` events)

| `errorCode` | Meaning |
|---|---|
| `PDF_NO_TEXT` | No extractable text — likely a scan (OCR out of scope) |
| `PDF_UNREADABLE` | Encrypted or corrupted PDF |
| `LLM_INVALID_OUTPUT` | LLM returned invalid/failed structured output after max 2 retries |
| `INTERNAL_ERROR` | Any other unexpected pipeline exception |

## Operational notes

- `jobId` = random UUID (unguessable; never sequential).
- Jobs and files live in memory / temp dir, TTL 24 h (`cvchecker.jobs.ttl`).
- LLM calls: HTTP/1.1 forced, read timeout 10 min (`cvchecker.llm.read-timeout`), model from `LLM_MODEL` env var, temperature 0.2.
