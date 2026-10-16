# API Contract — CV Checker workspace

Single origin: the frontend calls everything under `/api/*`. In dev, `frontend/proxy.conf.json`
routes `/api/*` → `http://localhost:8080/api/*` with **no path rewrite** — the backend owns the
`/api` prefix. No auth: it is a single-user, local-only app (plan §6.5, out of scope: accounts).

The app is a small **workspace**, not a form + job stack:

- The **CV** is uploaded once and kept by the backend. Every analysis snapshots it at creation.
- The **LLM model** is a global, persistent selection. Every analysis snapshots it at creation.
- Each **analysis** is one posting processed against that context. It runs as an async, **two-stage**
  pipeline: evaluation pauses for the user's approval, then the rewrite produces the new PDF.
- The posting can be **pasted as text** or **given as a link**; for a link the server fetches the
  page itself (§4, `POST /api/analyses`).
- A queued or running analysis can be **cancelled** and a cancelled or failed one can be
  **retried** (`POST /api/analyses/{id}/cancel`, `/retry`); a **link** analysis can be **re-run
  from scratch** with `/rerun` (re-fetch + re-evaluate, replacing its results).

`docs/API.md` is the contract; the backend and frontend are implemented against it. The old
`/api/cv-jobs*` endpoints and the per-job SSE streams were removed once the redesign shipped —
the deprecated model endpoints (`POST /api/llm/models/load`, `GET /api/llm/models/loaded`) as well.
Only the workspace API below exists.

---

## 1. Common types

### 1.1 Analysis stage

```
            (auto)                     (auto)                  (user approves)              (auto)
QUEUED ──▶ FETCHING_POSTING* ──▶ EVALUATING ──▶ AWAITING_REVIEW ──▶ REWRITING ──▶ GENERATING_PDF ──▶ COMPLETED
                                              ▲                          (accepts only      │
                                              │                          approved           ▼
                                              │                          suggestions)    FAILED (from any non-terminal stage)
                                              └────── FAILED ──▶ retry re-runs the failed stage

running/queued stages ──▶ CANCELLED ──▶ retry re-runs the interrupted stage
```

`FETCHING_POSTING` runs **only for link analyses** (before evaluation); pasted text goes straight to
`EVALUATING`. Every non-terminal stage is async. `AWAITING_REVIEW` is a stable waiting point: the
pipeline does **not** proceed until `POST /api/analyses/{id}/apply` is called.

**Cancellable stages:** `QUEUED`, `FETCHING_POSTING`, `EVALUATING`, `REWRITING`, `GENERATING_PDF`.
**Not cancellable:** `AWAITING_REVIEW`, `COMPLETED`, `FAILED`, `CANCELLED`.

`CANCELLED` remembers which stage was interrupted in `interruptedStage` so retry knows where to
resume. Retry (for `FAILED` or `CANCELLED`) re-runs the interrupted/failed stage from its start and
**keeps everything earlier stages produced** (fetched posting text, evaluation and suggestions, and
the approved suggestion ids when the rewrite was interrupted).

Every run carries an **`attemptId`**: it starts at 1 and increases by one each time a new run
starts (`apply`, `retry`). All state transitions, results and events carry the attempt id; a write
from an outdated attempt (a cancelled task that finishes anyway, cancel racing a stage completion,
cancel followed by a retry) is dropped by the server.

Indicative `progress` per stage: `QUEUED` 0, `FETCHING_POSTING` 5, `EVALUATING` 15, `AWAITING_REVIEW`
40, `REWRITING` 65, `GENERATING_PDF` 90, `COMPLETED` 100. A failed analysis keeps the progress
reached before the error. `stageMessage` is a transient, human-readable sub-stage message (e.g.
"Fetching the page" during `FETCHING_POSTING`) and `null` in every other stage.

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
  "shortDescription": "Payment APIs in Java and Spring Boot on a small platform team.",
  "progress": 40,
  "attemptId": 1,
  "interruptedStage": null,
  "stageMessage": null,
  "appliedAt": null,
  "createdAt": "2026-10-12T10:00:00Z",
  "updatedAt": "2026-10-12T10:03:12Z",
  "errorCode": null,
  "errorMessage": null
}
```

- `title` — auto-generated `Employer · Position` when evaluation finishes (see §6.2), otherwise
  `Untitled analysis · 12 Oct 2026`. A user edit sticks and is never overwritten.
- `score` — the 0-100 match score once evaluation is done, otherwise `null`.
- `shortDescription` — one or two sentences (capped at 200 characters) describing what the role is
  about, produced by the same evaluation call (see §6.4). `null` until evaluation is done and for
  analyses created before the field existed. A user edit (PATCH `description`, §4) replaces it and is
  never overwritten by a re-evaluation.
- `appliedAt` — the local date (`"2026-10-14"`) the user applied to the job, or `null` when not
  applied: *applied means `appliedAt != null`*. User-set via `PATCH`; the pipeline never changes it.
- `attemptId` — run counter, starts at 1, +1 per started run (see §1.1).
- `interruptedStage` — the stage a `CANCELLED` analysis stopped in; `null` otherwise.
- `stageMessage` — transient sub-stage message during `FETCHING_POSTING`, `null` otherwise.

Analysis detail (`GET /api/analyses/{id}`) — summary **plus**:

```json
{
  "id": "3f7c…-uuid",
  "title": "Acme · Senior Java Developer",
  "stage": "AWAITING_REVIEW",
  "score": 58,
  "shortDescription": "Payment APIs in Java and Spring Boot on a small platform team.",
  "progress": 40,
  "attemptId": 1,
  "interruptedStage": null,
  "stageMessage": null,
  "appliedAt": "2026-10-14",
  "createdAt": "…",
  "updatedAt": "…",
  "modelId": "qwen…/iq3_s.gguf",
  "errorCode": null,
  "errorMessage": null,
  "sourceUrl": null,
  "postingText": "…the posting text used for this analysis…",
  "postingSource": "PASTED",
  "postingTruncated": false,
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

- `sourceUrl` — the link the user provided, or `null` for a pasted posting.
- `postingText` — the resolved posting text the evaluation used. For a pasted analysis it equals the
  pasted text; for a link analysis it is what the server extracted (`null` until the fetch succeeds).
- `postingSource` — `PASTED | JSON_LD | STATIC_HTML | RENDERED_HTML` (`null` until resolved).
- `postingTruncated` — `true` when extraction hit the posting length limit and the text was cut.
- `summary`, `matchedKeywords`, `missingKeywords`, `suggestions` — present once evaluation done,
  otherwise `null`/`[]`.
- `appliedChanges` — present once the rewrite ran, otherwise `null`.
- `resultUrl` — present only when `stage == COMPLETED` and the file exists.
- Suggestions have stable backend-generated `id`s; `before`/`after` may be empty strings when the
  edit has no direct text pair. `priority` is `HIGH | MEDIUM | LOW`.

### `POST /api/analyses` — start a new analysis

Body (JSON) — provide **exactly one** of `jobPosting` (text) or `jobPostingUrl` (link):

```json
{ "jobPosting": "…" }
```

```json
{ "jobPostingUrl": "https://example.com/jobs/123" }
```

Snapshots the active CV (text) and the active model, creates a `QUEUED` analysis and starts the
pipeline. Returns immediately; processing runs asynchronously. For a link, the first stage is
`FETCHING_POSTING`: the server opens the page, extracts the posting (JSON-LD, static HTML or a
headless render — §4.1) and continues with the extracted text.

- **202**: the analysis summary (stage `QUEUED`; a link analysis moves to `FETCHING_POSTING`).
- **422** `POSTING_REQUIRED` — both fields absent or blank.
- **422** `POSTING_SOURCE_CONFLICT` — both fields present.
- **400** `JOB_DESCRIPTION_TOO_LONG` — pasted text > 20,000 characters.
- **422** `CV_MISSING` — no active CV (upload one first).
- **422** `MODEL_NOT_READY` — no ready model (load one in LM Studio first).

### 4.1 Link posting extraction

The server fetches the URL itself. It escalates and stops at the first step that yields a good
result:

1. **Plain HTTP fetch** (no browser). If schema.org `JobPosting` JSON-LD is present it is used
   directly (`postingSource = JSON_LD`, **no LLM call**). Otherwise readable text is extracted from
   the static HTML (`STATIC_HTML`).
2. **Headless browser render** (Playwright, Chromium) when step 1 yields too little text. JSON-LD is
   retried on the rendered DOM, then text extraction (`RENDERED_HTML`).
3. For any text that came from HTML extraction (not JSON-LD), one structured-output LLM call with
   the active model flags whether the page is a job posting and cleans navigation/ads out. If it is
   not a job posting the analysis fails with `POSTING_NOT_FOUND`.

Safety: only `http`/`https`; the hostname is resolved and loopback/private/link-local/unique-local/
multicast/unspecified/cloud-metadata addresses are blocked (checked on every redirect and every
sub-request the browser makes), with DNS-rebinding protection. Response size, content type
(`text/html`/`application/xhtml+xml` only) and timeouts are bounded. Logins, CAPTCHAs, bot
protection, proxies, cookies and site-specific scrapers are out of scope — a blocked page ends in a
friendly error telling the user to paste the text.

### `POST /api/analyses/{id}/cancel` — stop a queued or running analysis

- **200**: the updated summary (stage `CANCELLED`, `interruptedStage` set). Cleanup continues in the
  background; the analysis shows `CANCELLED` immediately. Cancelling an already-`CANCELLED`
  analysis is idempotent and returns the current summary.
- **409** `NOT_CANCELLABLE` — the analysis is in a stage that cannot be cancelled
  (`AWAITING_REVIEW`, `COMPLETED`, `FAILED`); see §1.1. A repeat on an already-`CANCELLED`
  analysis is **200**, not 409.
- **404** `ANALYSIS_NOT_FOUND`.

An `analysis.updated` event is broadcast for the transition.

### `GET /api/analyses` — list

- **200**: array of analysis summaries, newest first (empty array when none).

### `GET /api/analyses/{id}` — detail

- **200**: the analysis detail above.
- **404** `ANALYSIS_NOT_FOUND` — unknown id (the registry is in-memory; a backend restart clears it).

### `PATCH /api/analyses/{id}` — update title / description / applied to job

Partial update; every field is optional, at least one must be present. Allowed in **any** stage.
A success broadcasts an `analysis.updated` event, so other tabs and the card update live. User
values (`title`, `description`, `appliedAt`) are never overwritten by pipeline updates (retry,
re-evaluation, stage transitions keep them).

Body (each field optional; examples):

```json
{ "title": "Latest posting — Acme" }
```

```json
{ "description": "Payments platform role on a four-person Java team." }
```

```json
{ "applied": true }
```

```json
{ "appliedAt": "2026-10-14" }
```

- `title` — sets the user title (existing behaviour); marked as user-edited and never
  auto-overwritten.
- `description` — sets the user description (the role summary shown on the card and detail). The
  value is trimmed and capped at 200 characters; an empty/blank value **clears** the user override,
  so the field falls back to the evaluation's `shortDescription` (§6.4). Marked as user-edited and
  never overwritten by a re-evaluation.
- `applied` (`true` | `false`) — `true` sets `appliedAt` to **today** when it is not set yet and
  keeps the existing date when already set; `false` clears `appliedAt`.
- `appliedAt` — ISO date (`"2026-10-14"`) or ISO date-time; sets the date explicitly and implies
  applied. Time-of-day is reduced to the local date. Must not be in the future.
- When both `applied` and `appliedAt` are present, `appliedAt` wins.

- **200**: the updated summary.
- **400** `TITLE_BLANK` — a `title` field was sent but is blank/whitespace.
- **400** `DESCRIPTION_TOO_LONG` — a `description` field is longer than 200 characters.
- **422** `NO_UPDATES` — empty body or none of `title` / `description` / `applied` / `appliedAt`
  present.
- **422** `APPLIED_AT_INVALID` — `appliedAt` is not a parseable ISO date or date-time.
- **422** `APPLIED_AT_FUTURE` — `appliedAt` is after today.
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

### `POST /api/analyses/{id}/retry` — re-run the interrupted or failed stage

Allowed for `FAILED` and `CANCELLED` analyses. Re-runs the stage that failed (`failedStage`) or was
interrupted (`interruptedStage`) from its start, keeping earlier results (posting text, evaluation,
suggestions, approved suggestion ids) and going back to the queue.

- **202**: the updated summary (stage moved back to the failed/interrupted stage, `attemptId`
  already incremented, processing resumes).
- **409** `NOT_RETRYABLE` — the analysis is neither `FAILED` nor `CANCELLED`.
- **404** `ANALYSIS_NOT_FOUND`.

### `POST /api/analyses/{id}/rerun` — re-run a link analysis from scratch

Allowed only for a **link** analysis (`sourceUrl != null`) that is **not currently running**
(`AWAITING_REVIEW`, `COMPLETED`, `FAILED` or `CANCELLED`). Drops every stage result — the resolved
posting text, evaluation, suggestions, applied changes and the result PDF — and starts the pipeline
again from `QUEUED`, so the URL is **re-fetched** and everything is recomputed. `attemptId` is
incremented. User values (`title`, `description`, `appliedAt`) are preserved, and the model id stays
the one snapshotted at creation (no re-snapshot).

- **202**: the updated summary (stage `QUEUED`, `score` cleared).
- **409** `NOT_RERUNNABLE` — a pasted analysis, or one whose stage is currently running
  (QUEUED/FETCHING_POSTING/EVALUATING/REWRITING/GENERATING_PDF).
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
| `POSTING_REQUIRED` | 422 | neither `jobPosting` nor `jobPostingUrl` given |
| `POSTING_SOURCE_CONFLICT` | 422 | both `jobPosting` and `jobPostingUrl` given |
| `JOB_DESCRIPTION_TOO_LONG` | 400 | posting > 20,000 chars |
| `CV_MISSING` | 422 | analysis requires an active CV |
| `MODEL_NOT_READY` | 422 | analysis requires a ready model (see §3) |
| `ANALYSIS_NOT_FOUND` | 404 | unknown analysis id |
| `TITLE_BLANK` | 400 | `PATCH` with an empty `title` |
| `DESCRIPTION_TOO_LONG` | 400 | `PATCH` with a `description` over 200 characters |
| `NO_UPDATES` | 422 | `PATCH` with an empty body or no recognised fields |
| `APPLIED_AT_INVALID` | 422 | `PATCH` with a malformed `appliedAt` |
| `APPLIED_AT_FUTURE` | 422 | `PATCH` with an `appliedAt` after today |
| `NOT_AWAITING_REVIEW` | 409 | `apply` while not awaiting review |
| `NO_SUGGESTIONS_APPROVED` | 422 | `apply` with an empty approval list |
| `NOT_RETRYABLE` | 409 | `retry` while neither failed nor cancelled |
| `NOT_RERUNNABLE` | 409 | `rerun` on a pasted analysis or one that is currently running |
| `NOT_CANCELLABLE` | 409 | `cancel` while not in a cancellable stage |
| `NOT_READY` | 409 | result requested before completion |
| `RESULT_NOT_FOUND` | 404 | completed but the file was cleaned up |
| `LLM_INVALID_OUTPUT` | 502 | model output failed validation after max retries — retry |
| `LLM_ABORTED` | 502 | LLM call interrupted (e.g. model switch during the call) — retry after the switch |
| `INTERNAL_ERROR` | 500 | unexpected failure — retry |

**Stage error codes** (they appear in `errorCode` after the stage fails, not as HTTP responses; the
analysis is `FAILED` and retry re-runs the stage):

| `code` | Stage | Meaning / suggested action |
|---|---|---|
| `URL_INVALID` | `FETCHING_POSTING` | not an `http(s)` URL, contains credentials, or is too long |
| `URL_BLOCKED` | `FETCHING_POSTING` | the address (or a redirect/sub-request) is not publicly routable |
| `FETCH_FAILED` | `FETCHING_POSTING` | the page could not be fetched (timeout, network, oversized response) |
| `FETCH_NOT_HTML` | `FETCHING_POSTING` | the link does not point to a web page |
| `POSTING_NOT_FOUND` | `FETCHING_POSTING` | no usable job posting found; the site may need a login — paste the text instead |
| `INTERRUPTED` | any running stage | the backend restarted while the analysis was running; retry resumes the interrupted stage |

---

## 6. Model switching and pipeline behaviour

### 6.1 Safety rules (uncompromised)

- **No fabrication.** Prompts forbid adding experience, employers, dates, technologies or
  certificates not present in the CV. Posting requirements the CV lacks go into the report
  (`missingKeywords`, advice) — **never** into the CV text or into a suggestion that would invent it.
- CV and posting text are **untrusted input**: wrapped in delimiters, instructions inside ignored,
  content never logged (AGENTS.md). Files are deleted with the analysis (`DELETE`) or after TTL.

### 6.2 Title extraction

For a pasted posting, evaluation returns `employer` and `position`. For a link, JSON-LD
(`hiringOrganization.name`, `title`) or the extraction-cleanup call provides them before evaluation.
Unless the user already renamed the analysis, the backend sets `title = "<employer> · <position>"`;
empty extraction falls back to `Untitled analysis · <date>`. Evaluation can still refine the title
after a link fetch.

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

### 6.4 Short description of the position

The **same evaluation call** (no extra LLM request) also returns `shortDescription`: one or two
plain-text sentences (about 160 characters) describing what the role is about — main technologies,
product/domain, team or work mode when stated. It uses only facts present in the posting, never
invents, repeats no employer name already in the title, contains no markdown, and is written in the
language of the posting. The backend trims it and enforces a hard cap of 200 characters (cut at a
word boundary with an ellipsis); an empty or missing value stays empty. Retrying the evaluation
regenerates it. Analyses created before this field existed have none (the UI hides the line).

---

## 7. Live events — `GET /api/events`

**One SSE stream for the whole app**, per open tab. Event data is UTF-8 JSON. Every event carries a
**global** monotonically increasing numeric `id`. Removing per-job connections eliminates the
browser's per-domain connection limit (≈6 on HTTP/1.1) that previously froze job cards on
"Reconnecting…".

| Event | Sent when | `data` |
|---|---|---|
| `analysis.updated` | any stage/progress/title/score change for an analysis | analysis summary §4 |
| `analysis.output` | transient streamed LLM output while `EVALUATING`/`REWRITING` | `{ "analysisId": "…", "stage": "EVALUATING", "delta": "…" }` |
| `analysis.deleted` | an analysis was removed | `{ "analysisId": "…" }` |
| `models.updated` | the model state changed (selection, load, readiness) | `{}` (client re-fetches `GET /api/llm/models`) |
| `cv.updated` | the active CV was uploaded or replaced | `{}` (client re-fetches `GET /api/cv`) |
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
- `analysis.output` is **transient**: it carries no `id`, is not buffered and is never replayed, so it
  cannot advance a client's `Last-Event-ID` past real state events. The client shows it while a stage
  runs and drops it on the next `analysis.updated` for that analysis.
- The stream never closes for terminal states: with one global stream, a freshly created analysis
  must be observable on the same open connection. `analysis.deleted` tells tabs to drop a card.
- Context changes reach every tab too: `models.updated` and `cv.updated` keep the top-bar model
  select and CV chip in sync when another tab changes them.
- Disconnects are handled quietly: the client reconnects with `Last-Event-ID` and the backing HTTP
  server / dev proxy must not buffer or time out the stream.

---

## 8. Operational notes

- `analysisId` = random UUID (unguessable; the only access protection).
- Analyses are **persisted in SQLite** (one `analyses` table; the whole analysis state is a JSON
  snapshot alongside `id`/`created_at`/`updated_at`/`stage`/`applied_at` columns). The database file
  is `CV_DB_PATH` (default `./data/cvchecker.db`); a single-connection pool serialises writes. The
  API and the pipeline still work on the in-memory registry instance, which write-through persists on
  every change.
- **Restart semantics:** a non-terminal analysis (QUEUED, FETCHING_POSTING, EVALUATING, REWRITING,
  GENERATING_PDF) is restored as `FAILED` with `errorCode = "INTERRUPTED"` and its `failedStage` set
  to the stage it was in, so **retry resumes it**. `AWAITING_REVIEW`, `COMPLETED`, `FAILED` and
  `CANCELLED` analyses are restored as they were; a `COMPLETED` analysis whose result PDF was cleaned
  up serves 404 `RESULT_NOT_FOUND` as before.
- Temp files: the current CV file and result PDFs live in the temp dir; TTL cleanup
  (`cvchecker.analyses.ttl`, default 24 h) removes terminal analyses and their files. Original file
  bytes are never logged.
- Analyses are processed one at a time on the bounded executor (`cvchecker.analyses.max-concurrent`,
  default 1) — a local LLM handles one request at a time.
- LLM calls: HTTP/1.1, read timeout 10 min, temperature 0.2, max output 8192 tokens, strict JSON
  schema (LM Studio `response_format`) with validation + up to 2 corrective retries.
- Posting fetch (`cvchecker.posting-fetch.*`): `connect-timeout`, `read-timeout`, `total-timeout`,
  `max-bytes`, `max-redirects`, `min-text-length`, `browser-enabled` (default true), `user-agent`,
  `max-concurrent-browser`. The headless browser (Playwright Chromium) is installed once; if it is
  unavailable, the fetch skips the render step and fails gracefully.
- Cancellation: `POST /cancel` transitions to `CANCELLED` immediately and aborts the in-flight work
  in the background (HTTP connection, browser page/context, queue slot). The executor slot is freed
  promptly so a retry or the next queued analysis starts. Partial files from the cancelled attempt
  are deleted.
- SSE replay buffer bound: `cvchecker.events.replay-buffer` (default 1000 events).