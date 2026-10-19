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
- **409** `MODEL_SWITCH_BUSY` — an analysis is running; finish or cancel it before switching (§6.3).

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
  "requirements": [
    {
      "text": "Kubernetes",
      "type": "MUST",
      "status": "MISSING",
      "cvEvidence": ""
    },
    {
      "text": "Java and Spring Boot",
      "type": "MUST",
      "status": "MET",
      "cvEvidence": "Built payment APIs in Java and Spring Boot"
    }
  ],
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
      "priority": "HIGH",
      "needsConfirmation": false
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
- `requirements` — the posting's requirements once evaluation is done, otherwise `[]`. Each entry has
  `text` (the requirement), `type` (`MUST | NICE | SOFT`), `status` (`MET | PARTIAL | MISSING`) and
  `cvEvidence` (a verbatim CV quote for MET/PARTIAL, empty string for MISSING). `score` is derived
  from these statuses (see §6.5).
- `summary`, `matchedKeywords`, `missingKeywords`, `suggestions` — present once evaluation done,
  otherwise `null`/`[]`.
- `appliedChanges` — present once the rewrite ran, otherwise `null`.
- `resultUrl` — present only when `stage == COMPLETED` and the file exists.
- Suggestions have stable backend-generated `id`s; `before`/`after` may be empty strings when the
  edit has no direct text pair. `priority` is `HIGH | MEDIUM | LOW`. `needsConfirmation` is `true`
  when the proposed `after` text mentions words absent from the CV — the UI leaves it unchecked.

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

The analyze prompt may legitimately return an empty suggestions list (§6.1: "if nothing is worth
changing"). When the evaluation proposed no suggestions the body may be empty (or `{}`): the CV is
still regenerated — re-typeset for ATS with no content change — so the flow can reach the improved
CV instead of dead-ending at the review step.

- **202**: the updated summary (stage `REWRITING`).
- **409** `NOT_AWAITING_REVIEW` — the analysis is not in `AWAITING_REVIEW`.
- **422** `NO_SUGGESTIONS_APPROVED` — an empty list or only unknown ids while the evaluation *did*
  propose suggestions; approving nothing there is rejected so the CV is not regenerated unchanged.
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
| `MODEL_SWITCH_BUSY` | 409 | switch refused while an analysis is running (see §6.3) |
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
| `NO_SUGGESTIONS_APPROVED` | 422 | `apply` with an empty/unknown approval list while suggestions exist |
| `NOT_RETRYABLE` | 409 | `retry` while neither failed nor cancelled |
| `NOT_RERUNNABLE` | 409 | `rerun` on a pasted analysis or one that is currently running |
| `NOT_CANCELLABLE` | 409 | `cancel` while not in a cancellable stage |
| `NOT_READY` | 409 | result requested before completion |
| `RESULT_NOT_FOUND` | 404 | completed but the file was cleaned up |
| `GAP_TEXT_BLANK` | 400 | `POST`/`PATCH` a skill gap with blank text (§9) |
| `GAP_TEXT_TOO_LONG` | 400 | skill gap text > 300 characters |
| `GAP_NOT_FOUND` | 404 | unknown skill gap id |
| `REBUILD_RUNNING` | 409 | skill-gap regrouping already in progress (§9) |
| `CLUSTER_LABEL_BLANK` | 400 | rename a grouped skill with a blank label (§9) |
| `CLUSTER_LABEL_TOO_LONG` | 400 | grouped-skill label > 120 characters |
| `CLUSTER_NOT_FOUND` | 404 | unknown grouped-skill id |
| `IMPROVEMENT_RUNNING` | 409 | a standalone CV build is already in progress (§10) |
| `IMPROVEMENT_SKILLS_EMPTY` | 400 | no (non-blank) skills selected for the CV build |
| `IMPROVEMENT_SKILL_TOO_LONG` | 400 | a selected skill name > 120 characters |
| `IMPROVEMENT_TOO_MANY_SKILLS` | 400 | more than 30 skills selected |
| `LLM_INVALID_OUTPUT` | 502 | model output failed validation after max retries — retry |
| `LLM_ABORTED` | 502 | LLM call aborted because the analysis run was cancelled — restart the run |
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

Because LM Studio hosts **one** loaded model, a switch unloads the current one first — which would
abort any analysis using it. The switch is therefore **refused with `409 MODEL_SWITCH_BUSY`** while
an analysis is running:

- A run counts as running while it is in a cancellable stage (`QUEUED`…`GENERATING_PDF`).
- Analyses in `AWAITING_REVIEW` / `COMPLETED` (and other terminal stages) are unaffected — no LLM
  work is pending, so switching is allowed then.
- UI guidance: switch before starting analyses; the model picker is disabled while an analysis runs.

### 6.4 Short description of the position

The **same evaluation call** (no extra LLM request) also returns `shortDescription`: one or two
plain-text sentences (about 160 characters) describing what the role is about — main technologies,
product/domain, team or work mode when stated. It uses only facts present in the posting, never
invents, repeats no employer name already in the title, contains no markdown, and is written in the
language of the posting. The backend trims it and enforces a hard cap of 200 characters (cut at a
word boundary with an ellipsis); an empty or missing value stays empty. Retrying the evaluation
regenerates it. Analyses created before this field existed have none (the UI hides the line).

### 6.5 Deterministic evaluation checks

Because the model cannot be trusted to be self-consistent, the backend post-processes every
evaluation after the model call (prompts/analyze.st "Checks in code"), independent of the model:

1. `matchScore` is **recomputed** from `requirements` with the prompt's fixed formula (MUST 70%,
   NICE 20%, SOFT 10%, renormalised over the tiers that have items; a missing MUST caps it at 50) and
   overwrites the model's value.
2. A `missingKeywords` term that does appear in the CV (case-insensitive) is moved to
   `matchedKeywords`.
3. A suggestion is dropped when its `before` is not found in the CV (whitespace-normalised).
4. A suggestion is dropped when `before` and `after` are equal ignoring punctuation, whitespace and
   case.
5. A suggestion whose `after` mentions words not present in the CV is kept but flagged
   `needsConfirmation: true`, so the UI leaves it unchecked and asks the user to review.

The CV's own language is detected (Polish or English) and used to fill the analysis prompt so
`summary`/`problem`/`proposal`/`after` are written in the CV's language, not the posting's.

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
| `cv.improvement.updated` | the standalone CV build changes stage (§10) | CV build state §10 |
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

---

## 9. Skill gaps

A curated, cross-analysis view of what the CV lacks. Every analysis's evaluation carries
`requirements` (§4); the backend **aggregates** the ones whose `status == "MISSING"` (what the CV is
missing) or whose `type == "NICE"` (nice-to-haves) across **all** analyses, deduplicating them by a
normalized text key (case-insensitive, whitespace-collapsed) and counting how many analyses raised
each one. The user can then curate the list — rename, re-classify, mark progress, annotate, add their
own gaps, or dismiss — so it can drive a future CV rewrite.

Gaps are persisted (one `skill_gaps` table in the same SQLite database as analyses, docs/DECISIONS);
`cvchecker.persistence=memory` keeps them volatile like analyses. The sync runs on every `GET`: it
creates a **derived** row for each aggregated term that has none yet. User fields are **never**
overwritten by re-aggregation; a deleted derived gap is stored **dismissed** so it does not reappear.
`occurrences` and `sources` are recomputed live from the analyses on each read (a manual gap that no
analysis mentions reports `0`).

Gap item:

```json
{
  "id": "8c1e…-uuid",
  "text": "Kubernetes",
  "kind": "MISSING",
  "status": "OPEN",
  "note": "take the CKA next quarter",
  "origin": "DERIVED",
  "occurrences": 3,
  "sources": [ { "analysisId": "3f7c…", "title": "Acme · Senior Java Developer" } ],
  "createdAt": "2026-10-18T10:00:00Z",
  "updatedAt": "2026-10-18T10:05:00Z"
}
```

- `kind` — `MISSING` when at least one analysis marked the term missing, otherwise `NICE`.
- `status` — the user's progress: `OPEN` (default) | `PLANNED` | `LEARNED`.
- `origin` — `DERIVED` (aggregated from analyses) | `MANUAL` (user-created).
- `occurrences` / `sources` — how many analyses raised the term and which ones (derived only).

### `GET /api/skill-gaps` — aggregated + curated list

Syncs the aggregation into the store, then returns the non-dismissed gaps: missing first, then by
status, occurrence count (desc) and text. `200`: an array (empty when there is nothing yet).

### `POST /api/skill-gaps` — add a gap

Body:

```json
{ "text": "GraphQL", "kind": "NICE", "note": "want to learn" }
```

- `text` — required, non-blank, ≤ 300 characters.
- `kind` — `MISSING` | `NICE`; defaults to `MISSING`.
- `note` — optional, trimmed, ≤ 500 characters.

- **201**: the created item (`origin = "MANUAL"`, `status = "OPEN"`, `occurrences = 0`).
- **400** `GAP_TEXT_BLANK` / `GAP_TEXT_TOO_LONG`.

### `PATCH /api/skill-gaps/{id}` — update a gap

Partial update; every field is optional, at least one must be present. `text` recomputes the matching
key; user values are never touched by aggregation.

```json
{ "status": "LEARNED", "note": "course finished" }
```

- **200**: the updated item.
- **400** `GAP_TEXT_BLANK` / `GAP_TEXT_TOO_LONG`.
- **422** `NO_UPDATES` — empty body or none of `text` / `kind` / `status` / `note` present.
- **404** `GAP_NOT_FOUND`.

### `DELETE /api/skill-gaps/{id}` — remove a gap

- **204** — removed. A `MANUAL` gap is hard-deleted; a `DERIVED` one is dismissed so the next `GET`
  does not bring it back.
- **404** `GAP_NOT_FOUND`.

### `GET /api/skill-gaps/clusters` — grouped + ranked skills

The raw gap list keeps one entry per phrasing, so the same skill surfaces many times ("Docker and
Kubernetes" vs "znajomość rozwiązań Docker i Kubernetes"). A **background job** periodically folds
every gap into one bag, **translates the terms to English and groups the synonymous ones into a
single canonical skill** (LLM call #1), then **scores how important each canonical skill is in the
context of the analysed postings** (LLM call #2). Grouping is a coarse overview: it never touches the
curated gap rows. It runs automatically after every `cvchecker.skill-gaps.group-every` (default `5`)
completed analyses and on demand via `POST`.

```json
{
  "running": false,
  "lastRunAt": "2026-10-18T10:00:00Z",
  "lastError": null,
  "clusters": [
    {
      "id": "7b2a…-uuid",
      "label": "Kubernetes",
      "importance": 4,
      "rationale": "required as a must-have in most postings",
      "occurrences": 5,
      "kind": "MISSING",
      "members": [
        { "text": "znajomość rozwiązań Docker i Kubernetes", "kind": "MISSING", "occurrences": 2 }
      ],
      "updatedAt": "2026-10-18T10:00:00Z"
    }
  ]
}
```

- `label` — the model's canonical (English) skill name.
- `importance` — `1`–`5`, the model's judgement in the context of the analysed postings (`1` when it
  could not be scored).
- `occurrences` — the sum of its member gaps' live `occurrences` (each phrasing counts).
- `kind` — `MISSING` when any member is missing, otherwise `NICE`.
- `members` — the underlying (possibly Polish) gap phrasings folded into this skill.
- `running` / `lastRunAt` / `lastError` — the job's state; `clusters` is empty until the first run.

Sorted by `importance` (desc), then `occurrences` (desc), then `label`. `GET` returns the current
clusters and triggers **no** work; a run in flight is reported by `running`.

### `POST /api/skill-gaps/clusters/rebuild` — regroup now

Starts a run **synchronously** (the UI waits with a spinner) and returns the refreshed payload above.

- **200**: the refreshed payload (`running:false`, `lastRunAt` set).
- **409** `REBUILD_RUNNING` — a run is already in progress (the automatic job, or another click).
- **502** `LLM_INVALID_OUTPUT` — the model output failed validation.

### `PATCH /api/skill-gaps/clusters/{id}` — rename a grouped skill

Renames the canonical `label` of one cluster (the grouped view is a coarse, informational overview, so
editing it does not touch the curated gaps). The new label is persisted in the cluster store and
survives until the next regrouping run recomputes the labels from scratch.

```json
{ "label": "Cloud & Kubernetes" }
```

- `label` — required, non-blank, ≤ 120 characters.
- **200**: the refreshed payload (same shape as `GET /api/skill-gaps/clusters`).
- **400** `CLUSTER_LABEL_BLANK` / `CLUSTER_LABEL_TOO_LONG`.
- **404** `CLUSTER_NOT_FOUND`.

### `DELETE /api/skill-gaps/clusters/{id}` — remove a grouped skill

Removes the cluster **and every underlying gap it grouped** — each member goes through the §9 gap
delete (a derived gap is dismissed so it does not reappear, a manual one is hard-deleted) — then drops
the cluster. The curated list and the next regrouping reflect the removal.

- **204** — removed.
- **404** `CLUSTER_NOT_FOUND`.

Skill gaps are a personal backlog, not tied to one analysis: deleting an analysis does not remove its
aggregated gaps (they simply stop being re-counted and disappear only if no other analysis raises
them, once dismissed or never seen again). Changes — including a finished regrouping — are **not**
pushed over SSE; the view refreshes on open and after each action.

---

## 10. CV builder — improve the CV with selected skills

**Independent of the analysis flow** (no job posting): the user picks skills — typically the grouped
canonical skills from §9 — and the backend reshapes the **active CV** (§2) into an ATS-formatted
document that contains those skills in its SKILLS section, leaving every other fact unchanged. There
are no approved suggestions and no posting here: the only content change is the user-selected skill
list (the app is a CV builder, not a skill tracker). The PDF is produced by the same renderer as §4.

This is a single, **current** artifact (no per-analysis history, no id): one build runs at a time on
a dedicated executor. Starting a new build replaces the previous result, deleting its temp file.
Progress is broadcast as `cv.improvement.updated` (§7).

### `POST /api/cv/improvement` — build an improved CV

Body:

```json
{ "skills": ["Kubernetes", "GraphQL"] }
```

- `skills` — required, non-empty; trimmed and de-duplicated; each ≤ 120 characters; at most 30.
- Requires an active CV (§2) and a ready model (§3).

- **202**: the current build state (stage `REWRITING`).
- **400** `IMPROVEMENT_SKILLS_EMPTY` — no (non-blank) skills.
- **400** `IMPROVEMENT_SKILL_TOO_LONG` — a skill name over the limit.
- **400** `IMPROVEMENT_TOO_MANY_SKILLS` — more than 30 skills.
- **409** `IMPROVEMENT_RUNNING` — a build is already in progress.
- **422** `CV_MISSING` — no active CV.
- **422** `MODEL_NOT_READY` — no ready model (see §3).

### `GET /api/cv/improvement` — current build state

- **200**:

```json
{
  "stage": "COMPLETED",
  "skills": ["Kubernetes", "GraphQL"],
  "modelId": "qwen2.5-7b-instruct",
  "startedAt": "2026-10-18T10:00:00Z",
  "finishedAt": "2026-10-18T10:02:00Z",
  "errorCode": null,
  "errorMessage": null,
  "resultUrl": "/api/cv/improvement/result?v=7b2a…-uuid"
}
```

- `stage` — `NONE` (never run) | `REWRITING` (LLM) | `RENDERING` (PDF) | `COMPLETED` | `FAILED`.
- `skills` / `modelId` / `startedAt` — the inputs of the current (or last) build.
- `errorCode` / `errorMessage` — set when `FAILED` (`LLM_INVALID_OUTPUT` | `LLM_ABORTED` | `INTERNAL_ERROR`).
- `resultUrl` — the download path; the `v` query is a cache-buster (the run id); `null` unless `COMPLETED`.

### `GET /api/cv/improvement/result` — download the improved PDF

- **200**: `Content-Type: application/pdf`,
  `Content-Disposition: attachment; filename="cv-improved.pdf"`.
- **409** `NOT_READY` — no completed build yet.
- **404** `RESULT_NOT_FOUND` — completed, but the file was cleaned up (e.g. a newer build replaced it).

Layout note: the renderer targets **one page** and never lets a tiny overflow spill onto a sparse
extra page (it compacts spacing/size within a bounded range to fit the content).