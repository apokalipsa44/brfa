# AGENTS.md — rules for agents working in this repo

CV checker: upload a CV PDF + a job posting → a local LLM (LM Studio) evaluates the CV,
rewrites it, and renders a new ATS PDF. Two independent projects: `backend/` (:8080) and
`frontend/` (:4200, proxies `/api` → :8080).

Source of truth: `PLAN.en.md` + `docs/API.md` (contract) + `docs/DECISIONS.md` (decision log).

> **PLAN is partly stale.** §6.2's `job`/`pipeline` packages and the §9 milestone table predate
> the "persistent CV + model context + analysis" redesign (legacy job workspace was removed).
> Trust the current packages below and `docs/API.md`, not PLAN's package/milestone lists.

## Stack (verified 2026-10-14)

| Layer | Technology |
|---|---|
| Frontend | Angular 22.x — standalone, signals, **zoneless** (no zone.js dep), Tailwind 4, Vitest, ESLint |
| Backend | Java 21 (`java.version`) on JDK 25, Spring Boot **4.1.1**, Spring MVC, virtual threads, Maven `./mvnw` |
| LLM | Spring AI **2.0.1** (`spring-ai-starter-model-openai`) → LM Studio OpenAI-compatible API |
| Push | one global SSE stream (`SseEmitter`) |
| PDF | Apache PDFBox **3.0.8** for extraction **and** rendering (embedded DejaVu Sans for Polish chars) |

Boot 4 renamed the web starter to `spring-boot-starter-webmvc`; test starters are
`spring-boot-starter-{webmvc,actuator}-test`. Do not "fix" these names.

## Packages (`pl.example.cvchecker`)

`config`, `api`, `analysis`, `sse`, `llm`, `pdf`, `posting`, `storage` — not the PLAN §6.2 list.

## Commands

```bash
# backend (from backend/); does NOT need LM Studio running
./mvnw test                      # 2 live tests are skipped unless LM_STUDIO_LIVE=1
LM_STUDIO_LIVE=1 LLM_MODEL=<id> ./mvnw test   # slow live LM Studio tests (minutes)
./mvnw test -Dtest=ClassName     # single test/class
LLM_MODEL=<id> ./mvnw spring-boot:run   # :8080; model is env-configured, never hardcoded

# frontend (from frontend/)
npm run start                    # :4200; if taken: npm run start -- --port 4300
npm run build
npm run lint                     # eslint must be clean
npx ng test --watch=false        # Vitest; `ng test` watches by default in a TTY
```

Backend SSE/API integration tests set `cvchecker.pipeline.mode=simulated` themselves (no test
resources dir); `mode=real` in `application.yml` is the PDF+LLM+PDF flow. Live LLM tests are gated
by `@EnabledIfEnvironmentVariable(LM_STUDIO_LIVE=1)`.

## Configuration gotchas

- `application.yml` lives only in `backend/src/main/resources`.
- `LLM_BASE_URL` **must end in `/v1`** (Spring AI appends only `/chat/completions`).
- `LM_STUDIO_BASE_URL` is the native v1 API root and must **not** have `/v1` — different var from the above.
- `LLM_MODEL` defaults to `unconfigured`; `SPRING_AI_OPENAI_API_KEY` is a placeholder (`lm-studio`); no secrets in repo.
- Actuator base path is `/api/actuator`, so every backend URL sits under the single `/api` prefix.
- Prompt templates + strict JSON schemas live in `backend/src/main/resources/prompts/`.

## Conventions

- Java: records for DTOs, constructor injection only (no `@Autowired` fields).
- Angular: standalone components only, signals as state source, `@if/@for/@switch`, `inject()`,
  `OnPush`. Files are kebab-case **without** a `.component` suffix (e.g. `cv-dropzone.ts`,
  `analyses.store.ts`); many components use inline `template`. Selector prefix `app`.
- Root `tsconfig.json` does **not** enable `strict: true` (only `noImplicit*` + Angular `strict*`).
- Prettier config exists (`.prettierrc`, width 100, single quotes) but there is **no** npm format
  script — run `npx prettier --write src` if needed.
- CV/posting text is untrusted input (prompt injection): wrap it in delimiters before sending to the
  LLM; never log its content.
- Contract changes go into `docs/API.md` first, then code.
- Minor decisions: decide yourself and record one line in `docs/DECISIONS.md`. Architectural
  questions: ask the user.

## Tooling

- `.mcp.json` registers the Angular CLI MCP server (`npx @angular/cli mcp`).
- Repo skills live in `.opencode/skills/`: `angular-practices`, `spring-practices`,
  `codebase-search`. Load the relevant one when editing that stack (requires an opencode restart
  after adding/changing skills).

## Milestone rule

Do not start milestone N+1 until milestone N's acceptance criterion is met. Commit per milestone
with message prefix `M<n>:` (see `git log`).
