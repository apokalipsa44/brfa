# API Contract — CV Checker workspace

Single origin: the frontend calls everything under `/api/*`. In dev, `frontend/proxy.conf.json`
routes `/api/*` → `http://localhost:8080/api/*` with **no path rewrite** — the backend owns the
`/api` prefix. No auth: it is a single-user, local-only app (plan §6.5, out of scope: accounts).

The app is a small **workspace**, not a form + job stack:

- The **CV** is uploaded once and kept by the backend. Every analysis snapshots it at creation.
- The **LLM model** is a global, persistent selection. Every analysis snapshots it at creation.
- Each **analysis** is one posting processed against that context. It runs as an async, **two-stage**
  pipeline: evaluation pauses for the user's approval, then the rewrite produces the new PDF.

`docs/API.md` is the contract; the backend and frontend are implemented against it. Old
`/api/cv-jobs*` endpoints are deprecated and removed with the final iteration (see §7).

---

## 1. Common types

### 1.1 Analysis stage

```
            (auto)                  (user approves)              (auto)
QUEUED ──▶ EVALUATING ──▶ AWAITING_REVIEW ──▶ REWRITING ──▶ GENERATING_PDF ──▶ COMPLETED
                  ▲                          (accepts only      │
                  │                          approved           ▼
                  │                          suggestions)    FAILED (from any non-terminal stage)
                  └────── FAILED ──▶ retry re-runs the failed stage
```

Every non-terminal stage is async. `AWAITING_REVIEW` is a stable waiting point: the pipeline does
**not** proceed until `POST /api/analyses/{id}/apply` is called.

Indicative `progress` per stage: `QUEUED` 0, `EVALUATING` 15, `AWAITING_REVIEW` 40, `REWRITING` 65,
`GENERATING_PDF` 90, `COMPLETED` 100. A failed analysis keeps the progress reached before the error.

### 1.2 Error format — RFC 9457 `ProblemDetail`

All error responses use:

```json
{
  "type": "https://cvchecker.local/errors/file-too-large",
  "title": "File too large",
  "status": 413,
  "detail": "The PDF is 7.2 MB; the limit is 5 MB.",
  "instance": "/api/cv",
  "code": "FILE_TOO_LARGE"
}
```

`code` (extension property) is the stable machine-readable identifier the frontend maps to
friendly text (§5). Framework-level rejections keep Spring's status with a derived
`BAD_REQUEST / NOT_FOUND / METHOD_NOT_ALLOWED / UNSUPPORTED_MEDIA_TYPE / REQUEST_REJECTED`.

---

## 2. Persistent context: the CV

The backend holds exactly **one** active CV. Uploading replaces it; analyses already created keep a
snapshot of the CV (file name + extracted text) they used, so replacing the CV never changes an
existing analysis.

### `POST /api/cv` — upload / replace the active CV

`multipart/form-data`, field `file`.

| Field | Type | Rules |
|---|---|---|
| `file` | PDF file | required; ≤ 5 MB; `Content-Type: application/pdf` **and** `%PDF-` magic bytes |

The text is **extracted immediately**. On failure the previous CV stays active (a scan is never
accepted as the workspace CV; OCR is out of scope).

- **201**:

```json
{ "id": "9a1f…-uuid", "fileName": "cv.pdf", "status": "READY" }
```

- **400** `MISSING_FILE` — no `file` part.
- **413** `FILE_TOO_LARGE` — > 5 MB.
- **415** `INVALID_PDF` — wrong content type or missing signature.
- **422** `PDF_NO_TEXT` — no extractable text (likely a scan).
- **422** `PDF_UNREADABLE` — encrypted or corrupted PDF.

### `GET /api/cv` — current CV info

- **200**: same body as above (metadata only; the PDF is never served back).
- **404** `CV_NOT_FOUND` — no CV uploaded yet (or the backend restarted).

---

## 3. Persistent context: the LLM model

The backend stores the **active model id** and applies it to every LLM call. It reads LM Studio's
native `GET /api/v1/models` (all downloaded models with their `loaded_instances`) and loads via
native `POST /api/v1/models/load` (unload-then-load, see §6). Only LLM models
(`type == "llm"`) are exposed.

### `GET /api/llm/models` — model state

- **200**:

```json
{
  "models": [ { "id": "qwen…/iq3_s.gguf", "loaded": true }, { "id": "…", "loaded": false } ],
  "activeModelId": "qwen…/iq3_s.gguf",
  "ready": true,
  "reason": null
}
```

- `models` — every model LM Studio knows, `loaded` = has a loaded instance right now.
- `activeModelId` — the persistent selection, or `null` when none was ever selected.
- `ready` — **true only when** the backend can actually use the model: the active id is set, that
  model has a loaded instance **and** answers a 1-token probe on `/v1/chat/completions`.
- `reason` — human-readable explanation when `ready` is false: *"No model selected"*,
  *"The selected model is not loaded"*, *"LM Studio unreachable at …"*, *"LM Studio did not answer"*.
  The endpoint never crashes and never returns an error for an unreachable LM Studio — it reports
  `ready: false` with a reason.

### `PUT /api/llm/active-model` — select the active model

Body:

```json
{ "modelId": "qwen…/iq3_s.gguf" }
```

Selects the model, ensures it is loaded (best-effort unload of currently loaded instances, then
load), probes it and returns the resulting state:

- **200**: same `ModelState` body as `GET /api/llm/models`. If LM Studio is down or the load fails,
  the selection is still recorded and the body reports `ready: false` with a `reason` — the client
  shows the state instead of a crash. A `models.updated` SSE event is broadcast.
- **400** `MODEL_BLANK` — `modelId` missing/blank.
- **400** `MODEL_NOT_FOUND` — the id is not among the models LM Studio knows.

---

## 4. Analyses

### Types

Analysis summary (list entry, `analysis.updated` payload, PATCH/POST response):

```json
{
  "id": "3f7c…-uuid",
  "title": "Acme · Senior Java Developer",
  "stage": "AWAITING_REVIEW",
  "score": 58,
  "progress": 40,
  "createdAt": "2026-10-12T10:00:00Z",
  "updatedAt": "2026-10-12T10:03:12Z",
  "errorCode": null,
  "errorMessage": null
}
```

- `title` — auto-generated `Employer · Position` when evaluation finishes (see §6.2), otherwise
  `Untitled analysis · 12 Oct 2026`. A user edit sticks and is never overwritten.
- `score` — the 0-100 match score once evaluation is done, otherwise `null`.

Analysis detail (`GET /api/analyses/{id}`) — summary **plus**:

```json
{
  "id": "3f7c…-uuid",
  "title": "Acme · Senior Java Developer",
  "stage": "AWAITING_REVIEW",
  "score": 58,
  "progress": 40,
  "createdAt": "…",
  "updatedAt": "…",
  "modelId": "qwen…/iq3_s.gguf",
  "errorCode": null,
  "errorMessage": null,
  "summary": "The CV matches the core stack but lacks recent container-experience keywords.",
  "matchedKeywords": ["Java", "Spring Boot"],
  "missingKeywords": ["Kubernetes", "Terraform"],
  "suggestions": [
    {
      "id": "s1",
      "section": "Skills",
      "problem": "The posting asks for Kubernetes but the Skills section omits the containers entry.",
      "proposal": "Reword the skills list so existing container knowledge is grouped and keyworded.",
      "before": "Docker, CI/CD",
      "after": "Docker, Kubernetes (basics), CI/CD",
      "priority": "HIGH"
    }
  ],
  "appliedChanges": [
    { "section": "Skills", "before": "Docker, CI/CD", "after": "Docker, Kubernetes (basics), CI/CD", "reason": "…" }
  ],
  "resultUrl": null
}
```

- `summary`, `matchedKeywords`, `missingKeywords`, `suggestions` — present once evaluation done,
  otherwise `null`/`[]`.
- `appliedChanges` — present once the rewrite ran, otherwise `null`.
- `resultUrl` — present only when `stage == COMPLETED` and the file exists.
- Suggestions have stable backend-generated `id`s; `before`/`after` may be empty strings when the
  edit has no direct text pair. `priority` is `HIGH | MEDIUM | LOW`.

### `POST /api/analyses` — start a new analysis

Body (JSON):

```json
{ "jobPosting": "…" }
```

Snapshots the active CV (text) and the active model, creates a `QUEUED` analysis and starts stage 1.
Returns immediately; processing runs asynchronously.

- **202**: the analysis summary (stage `QUEUED`).
- **400** `MISSING_JOB_DESCRIPTION` — absent/blank.
- **400** `JOB_DESCRIPTION_TOO_LONG` — > 20,000 characters.
- **422** `CV_MISSING` — no active CV (upload one first).
- **422** `MODEL_NOT_READY` — no ready model (load one in LM Studio first).

### `GET /api/analyses` — list

- **200**: array of analysis summaries, newest first (empty array when none).

### `GET /api/analyses/{id}` — detail

- **200**: the analysis detail above.
- **404** `ANALYSIS_NOT_FOUND` — unknown id (the registry is in-memory; a backend restart clears it).

### `PATCH /api/analyses/{id}` — rename

Body:

```json
{ "title": "Latest job — Acme" }
```

- **200**: the updated summary. Marks the title as user-edited (never auto-overwritten).
- **400** `TITLE_BLANK` — blank/whitespace title.
- **404** `ANALYSIS_NOT_FOUND`.

### `POST /api/analyses/{id}/apply` — approve suggestions, start stage 2

Body:

```json
{ "approvedSuggestionIds": ["s1", "s2"] }
```

Starts `REWRITING` with **only** the approved suggestions (unknown ids are ignored). The rewrite
must not apply anything else; missing requirements stay out of the CV (§6.1).

- **202**: the updated summary (stage `REWRITING`).
- **409** `NOT_AWAITING_REVIEW` — the analysis is not in `AWAITING_REVIEW`.
- **422** `NO_SUGGESTIONS_APPROVED` — empty list; approving nothing is rejected so the CV is never
  regenerated unchanged.
- **404** `ANALYSIS_NOT_FOUND`.

### `POST /api/analyses/{id}/retry` — re-run the failed stage

- **202**: the updated summary (stage moved back to the failed stage and processing resumes).
- **409** `NOT_FAILED` — the analysis is not `FAILED`.
- **404** `ANALYSIS_NOT_FOUND`.

### `GET /api/analyses/{id}/result` — download improved PDF

- **200**: `Content-Type: application/pdf`,
  `Content-Disposition: attachment; filename="cv-improved-{id}.pdf"`.
- **409** `NOT_READY` — not `COMPLETED` yet.
- **404** `ANALYSIS_NOT_FOUND` / `RESULT_NOT_FOUND` — unknown id or file cleaned up.

### `DELETE /api/analyses/{id}` — remove an analysis and its files

- **204** — removed. Other analyses are unaffected.
- **404** `ANALYSIS_NOT_FOUND`.

---

## 5. Error codes

| `code` | Status | Meaning / suggested action |
|---|---|---|
| `MISSING_FILE` | 400 | No `file` part in the upload |
| `FILE_TOO_LARGE` | 413 | PDF > 5 MB |
| `INVALID_PDF` | 415 | Not `application/pdf` or missing `%PDF-` signature |
| `PDF_NO_TEXT` | 422 | Scan or no extractable text — OCR is out of scope |
| `PDF_UNREADABLE` | 422 | Encrypted/corrupted PDF |
| `CV_NOT_FOUND` | 404 | No active CV; upload one first |
| `MODEL_BLANK` | 400 | `modelId` missing in the request body |
| `MODEL_NOT_FOUND` | 400 | `modelId` unknown to LM Studio |
| `LLM_UNREACHABLE` | 502 | LM Studio down or not answering (model management) |
| `MISSING_JOB_DESCRIPTION` | 400 | posting missing/blank |
| `JOB_DESCRIPTION_TOO_LONG` | 400 | posting > 20,000 chars |
| `CV_MISSING` | 422 | analysis requires an active CV |
| `MODEL_NOT_READY` | 422 | analysis requires a ready model (see §3) |
| `ANALYSIS_NOT_FOUND` | 404 | unknown analysis id |
| `TITLE_BLANK` | 400 | rename with empty title |
| `NOT_AWAITING_REVIEW` | 409 | `apply` while not awaiting review |
| `NO_SUGGESTIONS_APPROVED` | 422 | `apply` with an empty approval list |
| `NOT_FAILED` | 409 | `retry` while not failed |
| `NOT_READY` | 409 | result requested before completion |
| `RESULT_NOT_FOUND` | 404 | completed but the file was cleaned up |
| `LLM_INVALID_OUTPUT` | 502 | model output failed validation after max retries — retry |
| `LLM_ABORTED` | 502 | LLM call interrupted (e.g. model switch during the call) — retry after the switch |
| `INTERNAL_ERROR` | 500 | unexpected failure — retry |

---

## 6. Model switching and pipeline behaviour

### 6.1 Safety rules (uncompromised)

- **No fabrication.** Prompts forbid adding experience, employers, dates, technologies or
  certificates not present in the CV. Posting requirements the CV lacks go into the report
  (`missingKeywords`, advice) — **never** into the CV text or into a suggestion that would invent it.
- CV and posting text are **untrusted input**: wrapped in delimiters, instructions inside ignored,
  content never logged (AGENTS.md). Files are deleted with the analysis (`DELETE`) or after TTL.

### 6.2 Title extraction

Evaluation returns `employer` and `position`. Unless the user already renamed the analysis, the
backend sets `title = "<employer> · <position>"`; empty extraction falls back to
`Untitled analysis · <date>`.

### 6.3 Model switching while analyses run

Analyses run through one bounded executor (a local model handles one request at a time). Each
analysis records the model active **at creation** and sends that id with every LLM request.

Because LM Studio hosts **one** loaded model, a switch unloads the current one first:

- New analyses use whatever is loaded when they start.
- A switch during an **in-flight LLM call** can abort that call; the analysis then fails with
  `LLM_ABORTED` and the user retries after the switch.
- Analyses in `AWAITING_REVIEW` / `COMPLETED` are unaffected (no LLM work pending).
- UI guidance: prefer to switch before starting analyses; the model picker flags this while
  analyses are active.

---

## 7. Live events — `GET /api/events`

**One SSE stream for the whole app**, per open tab. Event data is UTF-8 JSON. Every event carries a
**global** monotonically increasing numeric `id`. Removing per-job connections eliminates the
browser's per-domain connection limit (≈6 on HTTP/1.1) that previously froze job cards on
"Reconnecting…".

| Event | Sent when | `data` |
|---|---|---|
| `analysis.updated` | any stage/progress/title/score change for an analysis | analysis summary §4 |
| `analysis.deleted` | an analysis was removed | `{ "analysisId": "…" }` |
| `models.updated` | the model state changed (selection, load, readiness) | `{}` (client re-fetches `GET /api/llm/models`) |
| `ping` | heartbeat, every ~15-20 s | `{}` |

Example:

```
id: 41
event: analysis.updated
data: {"id":"3f7c…","title":"Acme · Senior Java Developer","stage":"AWAITING_REVIEW","score":58,"progress":40,"createdAt":"…","updatedAt":"…","errorCode":null,"errorMessage":null}

id: 42
event: models.updated
data: {}
```

Connection / replay semantics:

- Typical connect (no `Last-Event-ID`): the client already loaded full state via REST on start, so
  the server sends **live events only**.
- Reconnect (`Last-Event-ID: <n>` header): the server replays buffered events with `id > n` (bounded
  buffer, default last 1000 events) then continues live. Nothing is lost, nothing is duplicated.
- Heartbeat `ping` keeps proxies from closing an idle connection — treated by the client as a
  liveness signal, **never** shown to the user.
- The stream never closes for terminal states: with one global stream, a freshly created analysis
  must be observable on the same open connection. `analysis.deleted` tells tabs to drop a card.
- Disconnects are handled quietly: the client reconnects with `Last-Event-ID` and the backing HTTP
  server / dev proxy must not buffer or time out the stream.

---

## 8. Operational notes

- `analysisId` = random UUID (unguessable; the only access protection).
- Analyses live in an **in-memory registry**; the registry is empty after a backend restart.
  Persistence is deliberately out of scope for this iteration (it would sit behind the registry
  interface).
- Temp files: the current CV file, per-analysis original copies and result PDFs live in the temp
  dir; TTL cleanup removes analyses/files older than 24 h. Original file bytes are never logged.
- LLM calls: HTTP/1.1, read timeout 10 min, temperature 0.2, max output 8192 tokens, strict JSON
  schema (LM Studio `response_format`) with validation + up to 2 corrective retries.
- SSE replay buffer bound: `cvchecker.events.replay-buffer` (default 1000 events).