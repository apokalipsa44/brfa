# IMPLEMENTATION PLAN: CV Checker (core)

> Document for the AI agent implementing the project. Read it in full before writing the first line of code.
> Scope: **core only** (CV upload + job posting → LLM analysis → improved CV → completion notification).
> Out of scope: login, users, file history, database, payments (see section 11).

---

## 1. Goal and behavior

The user uploads a CV (PDF) and pastes the text of a job posting. The backend:

1. accepts the file and **immediately** replies "received" (HTTP 202),
2. reads the PDF content in the background,
3. uses a local LLM (LM Studio) to assess how well the CV matches the posting and what should change so that it is read well by ATS systems (Applicant Tracking Systems, the software recruiters use to filter CVs),
4. applies the corrections and generates a new PDF,
5. when finished, **pushes an event to the frontend** (Server-Sent Events), and the user downloads the finished file.

The whole process takes a long time (minutes), so **the frontend must not be blocked by any loader** while it runs. The user can do other things in the meantime, or even submit another CV.

### Key decision: "correcting the file"

Editing an existing PDF in place is very fragile (a PDF has no paragraph structure, only positioned text fragments). Therefore:

- **Default approach:** extract the content → the LLM returns the improved CV as **structured JSON** → the backend renders a **new PDF from its own ATS-friendly template** (single column, selectable text, standard section headings, no graphics or tables).
- Consequence: the original's visual design is **not preserved**. This is a deliberate decision. If the user wants something else, only the rendering module changes (section 6.7).

---

## 2. Stack and versions

| Layer | Technology |
|---|---|
| Frontend | **Angular (latest stable; at the time of writing this is the 22.x line)**, standalone components, **signals**, zoneless, Tailwind CSS |
| Backend | **Java + Spring Boot (latest stable, 4.x line / Spring Framework 7)**, Spring MVC |
| LLM | **Spring AI** (2.0 line, compatible with Spring Boot 4) + **LM Studio** (local OpenAI-compatible server) |
| Push to client | **Server-Sent Events** via `SseEmitter` (Spring MVC) |
| PDF in | Apache PDFBox 3.x |
| PDF out | HTML template (Thymeleaf) → PDF via openhtmltopdf (or OpenPDF) |
| Build | Maven (with `mvnw` wrapper) or Gradle, the agent's choice, but be consistent |

**Verify versions at the start** (start.spring.io, `npm view @angular/cli version`, Maven Central), since they may have changed since this plan was written. Do not hardcode versions from memory.

---

## 3. PHASE 0: environment check (before any code)

The agent must **check what is installed on this computer** and show the user a table: tool / version / status (OK, missing, too old).

| Tool | Check command | Requirement |
|---|---|---|
| Java JDK | `java -version`, `javac -version` | min. 21 (LTS); 25 is also fine. Check the requirements of the chosen Spring Boot version |
| Maven / Gradle | `mvn -v` or `./gradlew -v` | optional if the project uses a wrapper |
| Node.js | `node -v` | version required by the chosen Angular version (check the Angular docs or use the MCP) |
| npm | `npm -v` | compatible with Node |
| Angular CLI | `ng version` or `npx @angular/cli version` | latest stable |
| Git | `git --version` | any recent version |
| LM Studio | `curl http://localhost:1234/v1/models` | local server running, an **instruct-type model loaded** |

Rules:
- For missing tools, **install them or give the exact install command**. Before installing anything system-level (JDK, Node), ask the user.
- If LM Studio does not respond on `/v1/models`, **stop and ask the user to start it** (*Developer → Start Server* tab) and load a model. Do not guess the model name; read it from the `/v1/models` response.
- Save the result in `docs/ENVIRONMENT.md`.

### 3.1 Angular MCP (Angular documentation for the agent)

Use the **official MCP server built into the Angular CLI** (`ng mcp`). It gives the agent up-to-date documentation, code examples and best practices for the specific Angular version, instead of relying on outdated model knowledge. In version 22 the Angular MCP tooling is marked stable.

Configuration (choose the variant matching the agent you use):

```bash
# Claude Code
claude mcp add angular-cli -- npx -y @angular/cli mcp
```

```json
// generic mcp.json (Cursor, VS Code, Gemini CLI, etc.)
{
  "mcpServers": {
    "angular-cli": {
      "command": "npx",
      "args": ["-y", "@angular/cli", "mcp"]
    }
  }
}
```

Notes:
- This is **not the same** as the `@talzach/mcp-angular-cli` package (unofficial, it only wraps `ng generate/add/new`). We want `ng mcp` from `@angular/cli`.
- After adding it, restart the agent session and **confirm the MCP tools are visible** (e.g. documentation search, best practices, examples). The available toolset depends on the CLI version, so check it rather than assume.
- **For every frontend task, first fetch the best practices and search the documentation** (signals, `resource`, zoneless, forms), and only then write code.

---

## 4. Architecture

```
┌────────────┐  1. POST /api/cv-jobs (multipart)       ┌──────────────────────────┐
│  Angular   │ ───────────────────────────────────────▶ │ CvJobController          │
│  (signals) │ ◀─────────── 202 {jobId} ─────────────── │  validation → job QUEUED │
│            │                                          └──────────┬───────────────┘
│            │  2. GET /api/cv-jobs/{id}/events (SSE)              │ @Async (executor)
│ EventSource│ ◀═══════ status / completed / failed ═══╗           ▼
│            │                                         ║  ┌─────────────────────────┐
│            │  3. GET /api/cv-jobs/{id}/result (PDF)  ║  │ CvProcessingPipeline    │
│            │ ◀──────────────────────────────────────╫─ │ extract → analyze →     │
└────────────┘                                         ║  │ rewrite → render PDF    │
                                                       ║  └──────┬───────────┬──────┘
                                         ┌─────────────╨──┐      │           │
                                         │ SseHub         │◀─ ApplicationEvent   │
                                         │ (emitters +    │   (JobStatusChanged) │
                                         │  replay buffer)│                      ▼
                                         └────────────────┘          Spring AI ChatClient
                                                                            │
                                                                     LM Studio :1234
```

### 4.1 Why SSE and not WebSocket

Spring offers several push-to-client mechanisms:

| Mechanism | Description | Verdict for us |
|---|---|---|
| **SSE via `SseEmitter`** (Spring MVC) | One-way `text/event-stream` over HTTP, available since Spring Framework 4.2 | **CHOSEN.** We only need server → client. Plain HTTP; the browser's `EventSource` has built-in reconnect and `Last-Event-ID` |
| SSE via WebFlux (`Flux<ServerSentEvent<T>>`) | Same thing, reactive | Fine, but forces WebFlux. Spring AI `ChatClient` and multipart are simpler in MVC |
| WebSocket / STOMP | Bidirectional | Overkill. We don't need a client → server channel |
| Long polling / polling | Client asks periodically | Worse UX and load, rejected |

Decision: **Spring MVC + `SseEmitter` + virtual threads** (`spring.threads.virtual.enabled=true`), so open SSE connections do not occupy the server thread pool.

Inside the backend, use **`ApplicationEventPublisher`** (Spring events) for pipeline → SSE layer communication. The pipeline publishes `JobStatusChanged`, and `SseHub` has an `@EventListener` that distributes it to emitters. This way the pipeline knows nothing about SSE.

### 4.2 What Spring AI is and is not

Spring AI provides the `ChatClient` abstraction (prompts, templates, **structured output** into Java records, advisors, observability via Micrometer, optional token streaming). **It does not send statuses to the frontend.** Stage statuses ("analyzing", "rewriting") are emitted by our own pipeline (4.1). Token streaming (`.stream()`) is optional and **not needed for now**.

---

## 5. Repository structure

```
btfa/
├── AGENTS.md              # short rules for the agent (from this plan: stack, commands, conventions)
├── README.md
├── docs/
│   ├── ENVIRONMENT.md     # result of phase 0
│   └── API.md             # REST + SSE contract (section 7)
├── backend/               # Spring Boot
└── frontend/              # Angular
```

Initialize git and make **small commits per milestone** (section 9).

---

## 6. BACKEND

### 6.1 Dependencies (starter from start.spring.io)

- `spring-boot-starter-web` (MVC, SSE, multipart)
- `spring-boot-starter-validation`
- `spring-boot-starter-actuator`
- `spring-boot-starter-thymeleaf` (CV template)
- Spring AI: OpenAI-compatible starter (`spring-ai-starter-model-openai`, confirm the name in current Spring AI docs) + `spring-ai-bom`
- `org.apache.pdfbox:pdfbox` (3.x)
- `com.openhtmltopdf:openhtmltopdf-pdfbox` (+ `openhtmltopdf-slf4j`) or OpenPDF
- Lombok optional, Java records preferred for DTOs
- Test: `spring-boot-starter-test`

### 6.2 Packages

```
pl.example.cvchecker
├── config/        # AiConfig, AsyncConfig, WebConfig (CORS), properties
├── api/           # CvJobController, DTOs (records), GlobalExceptionHandler
├── job/           # CvJob, JobStatus/Stage, CvJobRegistry, JobEvents (JobStatusChanged)
├── sse/           # SseHub (emitters, replay buffer, heartbeat)
├── pipeline/      # CvProcessingPipeline (stage orchestration)
├── pdf/           # PdfTextExtractor, CvPdfRenderer
├── llm/           # CvAnalysisService, CvRewriteService, models (CvAnalysis, CvDocument)
└── storage/       # TempFileStorage (+ TTL cleanup)
src/main/resources/
├── prompts/       # analyze.st, rewrite.st (Spring AI prompt templates)
├── templates/     # cv.html (Thymeleaf)
└── fonts/         # embedded TTF font with full Polish character support
```

### 6.3 LM Studio configuration in Spring AI

LM Studio exposes an OpenAI-compatible API, so we use the OpenAI starter with a different `base-url`.

```yaml
spring:
  threads:
    virtual:
      enabled: true
  servlet:
    multipart:
      max-file-size: 5MB
      max-request-size: 6MB
  ai:
    openai:
      api-key: lm-studio            # any non-empty string, LM Studio ignores it
      base-url: http://localhost:1234
      chat:
        options:
          model: ${LLM_MODEL}       # name from GET /v1/models
          temperature: 0.2          # low: we want reliability, not creativity
cvchecker:
  llm:
    read-timeout: 10m
  jobs:
    max-concurrent: 1               # a local LLM processes one job at a time anyway
    ttl: 24h
```

Pitfalls to handle:

1. **HTTP/2.** LM Studio does not support HTTP/2, and the default HTTP client in Spring AI / Spring Boot may try to use it, which results in connection errors. Configure the `RestClient` (via `RestClient.Builder` / `ClientHttpRequestFactory`) to force **HTTP/1.1** (e.g. JDK `HttpClient` with `Version.HTTP_1_1`). Verify in the current Spring AI version whether the problem still exists, but the safeguard is cheap.
2. **Long timeouts.** A local model may take many minutes to respond. Set `read-timeout` explicitly, well above the default.
3. **Endpoint path.** Check that `base-url` plus the default chat completions path yields the correct URL (`/v1/chat/completions`); set `completions-path` if needed.
4. **Context window.** Set a sufficiently large context length in LM Studio for CV + posting + prompt. The backend must check input length and reject/truncate overly long text with a clear error.
5. **The model must be configurable** (environment variable), since it will be swapped for a hosted one. Do not tie the code to a specific model. For reasonable JSON quality and Polish language support, pick an *instruct* model of at least several billion parameters, and leave the model decision to the user.

### 6.4 Data model (in memory, no database)

```java
enum Stage { QUEUED, EXTRACTING_TEXT, ANALYZING, REWRITING, GENERATING_PDF, COMPLETED, FAILED }

record JobEvent(long id, String jobId, Stage stage, String message, Integer progress, Instant at, Object payload) {}

class CvJob {            // thread-safe
  String id; Stage stage; Instant createdAt;
  Path originalFile; Path resultFile;      // in the temp directory
  String jobDescription;
  CvAnalysis analysis;                      // report after completion
  List<JobEvent> events;                    // for replay
  String errorCode, errorMessage;
}
```

Hide `CvJobRegistry` (ConcurrentHashMap) behind an **interface**, so it can later be replaced with a database without changes to the rest of the code.

### 6.5 REST API (contract)

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/cv-jobs` | `multipart/form-data`: `file` (PDF), `jobDescription` (text). Returns **202 Accepted** `{ "jobId": "...", "stage": "QUEUED" }` and **finishes immediately** |
| `GET` | `/api/cv-jobs/{jobId}/events` | `text/event-stream`, the job's event stream |
| `GET` | `/api/cv-jobs/{jobId}` | State snapshot (to restore state after a page refresh) |
| `GET` | `/api/cv-jobs/{jobId}/result` | Download the improved PDF (`Content-Disposition: attachment`). `409` if not ready yet, `404` if missing |

Validation on `POST` (errors as `ProblemDetail` / RFC 9457):
- file present, ≤ 5 MB, `Content-Type: application/pdf` **and** a check of the `%PDF-` signature (don't trust the header alone),
- `jobDescription` non-empty, e.g. ≤ 20,000 characters,
- `400` / `413` / `415` with a clear message.

`jobId` is a random UUID (unguessable). Since there is no login, it is the only protection of access to the result, so do not use sequential IDs.

### 6.6 SSE events

Each event has an increasing `id` (within the job), a name (`event:`) and JSON in `data:`.

```
id: 3
event: status
data: {"jobId":"…","stage":"ANALYZING","message":"Analyzing CV match against the job posting","progress":35}

id: 7
event: completed
data: {"jobId":"…","stage":"COMPLETED","progress":100,"resultUrl":"/api/cv-jobs/…/result","report":{"matchScoreBefore":58,"changes":[…]}}

id: 5
event: failed
data: {"jobId":"…","stage":"FAILED","errorCode":"PDF_NO_TEXT","message":"No text found in the PDF (it may be a scan)"}
```

Indicative `progress` per stage: QUEUED 0, EXTRACTING_TEXT 10, ANALYZING 35, REWRITING 65, GENERATING_PDF 90, COMPLETED 100.

Requirements for `SseHub`:
- `new SseEmitter(0L)` or a very long timeout (a job takes minutes),
- **replay:** every event goes into a buffer in `CvJob`. After connecting, the client receives events from the buffer (starting after `Last-Event-ID` if given, otherwise from the beginning), and only then live ones. This solves the race "the job started before the client managed to open SSE". **Replay and appending new events happen under one lock per job**, so events are neither lost nor duplicated,
- if the job is already in a terminal state, send the replay and **close immediately** (`complete()`),
- **heartbeat** every ~15-20 s (SSE comment `:keepalive` or a `ping` event), so proxies/browsers don't close an idle connection,
- handle `onCompletion`, `onTimeout`, `onError` (remove the emitter from the registry), and when sending to multiple emitters make sure one broken emitter does not block the rest,
- after `completed` / `failed`, close all emitters of that job.

### 6.7 Processing pipeline

Launched via `@Async` on a dedicated `ThreadPoolTaskExecutor` (`max-concurrent` from configuration, bounded queue). The controller **does not wait** for the result. Each stage publishes `JobStatusChanged`. Any exception → a `failed` event with an error code, temp file cleaned up, the server **does not crash the thread**.

1. **EXTRACTING_TEXT.** PDFBox (`Loader.loadPDF` + `PDFTextStripper`). No text (scan) → `PDF_NO_TEXT`. OCR is out of scope. Encrypted/corrupted PDF → `PDF_UNREADABLE`.
2. **ANALYZING (LLM #1).** `ChatClient` with the `analyze.st` prompt. Input: CV text + posting. Structured output (`.entity(CvAnalysis.class)`):
   ```java
   record CvAnalysis(int matchScore, List<String> matchedKeywords, List<String> missingKeywords,
                     List<Suggestion> suggestions, String summary) {}
   record Suggestion(String section, String problem, String recommendation, Priority priority) {}
   ```
3. **REWRITING (LLM #2).** `rewrite.st` prompt. Input: CV text + posting + `CvAnalysis`. Output: `CvDocument` (personal data, summary, experience with bullet points, education, skills, languages, certifications) **plus a `changes` list** (`section`, `before`, `after`, `reason`) that we will show to the user.
4. **GENERATING_PDF.** `CvDocument` → Thymeleaf `cv.html` → openhtmltopdf → file in the temp directory.
5. **COMPLETED.** Store the result path, send `completed`.

Prompt rules (crucial for quality and honesty):
- **No fabrication.** The model may rephrase, reorder, emphasize and add keywords **only where they follow from facts already in the CV**. It must not add experience, positions, dates, technologies or certificates that are not there. Requirements from the posting that the CV lacks go into the report as a recommendation to the user, **not into the CV content**.
- **Preserve the CV's language** (Polish stays Polish, English stays English). Copy contact data, dates and company names unchanged.
- **CV and posting text is untrusted data (prompt injection).** Put it in clear delimiters (e.g. `<cv>…</cv>`, `<job>…</job>`) and state in the system prompt that text inside is only material for analysis and any instructions found in it must be ignored.
- **Structured output from a small local model can be unreliable.** Add: validation of the result (e.g. `matchScore` 0-100, non-empty sections), **retry (max 2 times)** on invalid JSON, and after exhausting attempts a `failed` event with code `LLM_INVALID_OUTPUT`. Consider JSON schema mode if LM Studio and the chosen model support it (check and note in `docs/ENVIRONMENT.md`).
- Keep prompts in `.st` files in `resources/prompts`, not as strings in code.
- ATS rules for the template and prompt: standard section names (Experience, Education, Skills), simple bullets, no tables/columns/images/icons, consistent date format, posting keywords used naturally.

### 6.8 PDF generation: pitfalls

- **Polish characters.** Embed a TTF font with the full character set (e.g. Noto Sans / DejaVu Sans / Liberation, pick one with a license that allows distribution) and register it in openhtmltopdf. Default PDF fonts do not support `ąęłńóśźż`. **Add a test that renders Polish characters and extracts them back via PDFBox.**
- The resulting PDF must have **selectable text** (not an image). Test: extract text from the generated PDF and compare it with `CvDocument`.
- The HTML template must be valid XHTML (an openhtmltopdf requirement).

### 6.9 Other backend requirements

- **CORS** for `http://localhost:4200` during development. Preferably use the Angular dev-server proxy instead (section 8.6), which makes CORS unnecessary.
- **Cleanup:** `@Scheduled` removes jobs and files older than `ttl`. The original file can be deleted right after the pipeline finishes (it contains personal data), so don't keep it longer than needed and **do not log CV or posting content**.
- **Global `@RestControllerAdvice`** returning `ProblemDetail`.
- **Actuator:** health + (optionally) stage timing metrics. Spring AI has built-in observability via Micrometer.
- Logging: stages, durations, error codes, `jobId`. No personal data.

### 6.10 Backend tests

just check if app is running after milestone change

---

## 7. Contract that frontend and backend must satisfy

Write it in `docs/API.md` **before** implementing either side (sections 6.5 and 6.6 are the source). Any contract change is made there first.

---

## 8. FRONTEND (Angular)

> **Before every task, use the Angular MCP** (best practices + documentation search). Apply current APIs instead of patterns from older versions.

### 8.1 Assumptions

- New workspace: `ng new frontend` (standalone, zoneless, SCSS not needed since Tailwind handles styling, SSR/prerendering **disabled**, it's just an SPA).
- **Signals** everywhere as the source of state: `signal`, `computed`, `effect` (sparingly), `input()`/`output()`, `inject()`. No `*ngIf/*ngFor`, only `@if/@for/@switch`.
- `ChangeDetectionStrategy.OnPush`, small presentational components, logic in services/stores.
- Forms: check in the documentation (via MCP) whether **Signal Forms** are stable in the version used. If so, use them; if not, reactive forms.
- One-off data fetching: `HttpClient` (+ optionally `httpResource`/`resource` if stable in this version).

### 8.2 Tailwind

- Install Tailwind **following the current instructions for Angular** (find them in the documentation via MCP, as the integration depends on the Tailwind version and builder).
- Style mainly with plain Tailwind. For accessible, complex elements (dialog, menu, tabs) use **Angular CDK** or **Angular Aria** components (unstyled) and style them with Tailwind. Ready-made Tailwind component libraries (e.g. Spartan UI) are **optional**. If you reach for a library, check its compatibility with the current Angular and Tailwind versions and tell the user what you are adding.
- Mobile-first responsiveness, dark mode optional.

### 8.3 Structure

```
src/app/
├── core/
│   ├── api/            # CvJobsApi (HttpClient: POST, GET snapshot, result URL)
│   └── sse/            # JobEventsService (EventSource → signal)
├── features/cv-check/
│   ├── cv-check.page.ts        # page: form + job list
│   ├── upload-form/            # PDF dropzone + posting textarea + button
│   ├── job-list/               # job list
│   └── job-card/               # status, progress bar, report, Download button
├── core/jobs.store.ts          # JobsStore: signal<Job[]>, actions, computed
└── shared/ (toast, icons, pipes)
```

### 8.4 UI behavior (the most important requirement)

1. The user picks a PDF (drag or click), pastes the posting, and clicks **Submit**.
2. The frontend sends `POST`. **The button is disabled only for the duration of the upload itself** (a short request). After **202**, the form clears immediately and becomes active again.
3. The new job appears in the list with a status such as "Queued / Analyzing / Rewriting…" and a **small progress indicator on the job card**. **No full-screen loader and no UI blocking.** The user can submit more CVs.
4. After `completed`, the card changes: match score, list of changes and remarks, a **Download improved CV** button + a **toast** ("Your CV is ready").
5. After `failed`, the card shows an understandable message and a "Try again" button.
6. Client-side validation: PDF only, ≤ 5 MB, non-empty posting (consistent with the backend). Show `ProblemDetail` errors to the user.

### 8.5 SSE client

- Use the native `EventSource` (Angular's HttpClient is not suited to SSE). Wrap it in `JobEventsService`:
  - `watch(jobId)` opens `new EventSource('/api/cv-jobs/{id}/events')`, listens to named events (`status`, `completed`, `failed`) via `addEventListener`, parses the JSON and **updates a signal in `JobsStore`**,
  - after `completed`/`failed` **always call `close()`** (otherwise `EventSource` will reconnect forever),
  - close on destroy (`DestroyRef`), no leaks,
  - `onerror`: `EventSource` reconnects by itself and sends `Last-Event-ID`, and the backend replays missed events (6.6). Show a subtle "reconnecting…" state in the UI if it takes longer.
- Browser limit: with HTTP/1.1 there are about 6 parallel connections per domain. Limit the number of simultaneously watched jobs (e.g. 3-4 active) or make sure HTTP/2 is used in the dev proxy/production.
- **Resilience to page refresh:** store the `jobId`s of active jobs in `localStorage`, and on app start fetch a snapshot (`GET /api/cv-jobs/{id}`) and resume SSE for unfinished ones. It is lightweight and noticeably improves UX. This is not "file history" (that is out of scope).

### 8.6 Development proxy

`proxy.conf.json` routes `/api` to `http://localhost:8080`, so the frontend and backend run "under one origin" and CORS is not needed. Make sure the proxy **does not buffer** SSE responses.

### 8.7 Frontend tests

check if app is biulding, check for linter error.

---

## 9. Implementation order (milestones)

After each milestone: build and check for errors, short commit, update `docs/` wait for my aproval.

| # | Milestone | Acceptance criterion |
|---|---|---|
| 0 | Phase 0: environment + Angular MCP | `docs/ENVIRONMENT.md` ready, MCP visible to the agent, LM Studio responds |
| 1 | Skeletons: `backend/` (Boot+Web), `frontend/` (Angular+Tailwind), proxy | Both apps start, the frontend calls `GET /actuator/health` through the proxy |
| 2 | Contract: `docs/API.md` | Approved REST + SSE contract |
| 3 | Upload + job registry + validation | `POST` returns 202 immediately, validation gives 400/413/415, tests green |
| 4 | `SseHub` + events (for now a **simulated pipeline** with `sleep` instead of the LLM) | Client sees successive stages, replay after late connection works, heartbeat works |
| 5 | Frontend: form, `JobsStore`, `JobEventsService`, job list, **no blocking loader** | Several jobs can be submitted, statuses update live, page refresh resumes SSE |
| 6 | Text extraction from PDF | Correct text from a PDF, `PDF_NO_TEXT` error for a scan |
| 7 | Spring AI + LM Studio integration (analysis step) | Real `CvAnalysis` from the local model, retry on bad JSON, HTTP/1.1 works |
| 8 | Rewrite step → `CvDocument` + `changes` | Valid JSON, no fabricated facts in test CVs |
| 9 | PDF rendering from template (font with Polish characters) | PDF has selectable text and correct `ąęłńóśźż`, automated test |
| 10 | Wire everything + `GET /result` + result card on frontend + toast + download | Full end-to-end flow on a real LM Studio |
| 11 | Polish: TTL cleanup, error handling, README with run instructions | A clean start from the README works, no emitter or file leaks |

**Rule:** do not move to the next milestone until the acceptance criterion of the previous one is met. Milestones 4 and 5 deliberately use a simulated pipeline so the whole event communication can be tested **without depending on the slow LLM**.

---

## 10. Conventions for the agent

- Java: records for DTOs, constructor injection, no `@Autowired` fields, clear packages per 6.2.
- Angular: standalone only, signals, new template syntax, `inject()`; strict TypeScript.
- No secrets in the repo; configuration via `application.yml` + environment variables (`LLM_MODEL`, `LLM_BASE_URL`).
- If you hit a decision the plan does not settle and that affects the architecture, **ask the user** instead of guessing. Decide minor things yourself and record them in `docs/DECISIONS.md` (one sentence: what and why).
- For every dependency, check the current documentation (Angular: MCP; Spring/Spring AI: official docs), don't rely on model memory.

---

## 11. Out of scope (do NOT implement for now)

Login and accounts, file history and database, payments, OCR for scans, editing the original PDF in place, multiple graphic CV templates, DOCX export, a cloud-hosted LLM (the architecture should allow it through Spring AI configuration changes, but we are not deploying it now), horizontal scaling (the job registry and emitters live in the memory of a single process).

---

## 12. Open questions for the user (the agent asks at the start if unanswered)

1. Do you accept that the improved CV is a **new PDF from an ATS template** (without preserving the original look)? *(default: yes)*
2. Maven or Gradle? *(default: Maven with wrapper)*
3. Which model is loaded in LM Studio, and in what language will most CVs be (PL/EN)?
4. Do you want to show the user the list of changes (`changes`) and the match score right away, or only the file? *(default: show them)*
