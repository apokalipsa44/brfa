# AGENTS.md — rules for agents working in this repo

Source of truth: `PLAN.en.md` (scope, architecture, milestones). This file is the short operating manual.

## Stack (verified 2026-10-09, see docs/ENVIRONMENT.md)

| Layer | Technology |
|---|---|
| Frontend | Angular 22.x — standalone components, signals, zoneless, Tailwind CSS (`--style tailwind`) |
| Backend | Java 21+ (JDK 25 installed), Spring Boot **4.1.1** (Spring Framework 7), Spring MVC |
| LLM | Spring AI **2.0.1** (`spring-ai-starter-model-openai`) → LM Studio `http://localhost:1234` (OpenAI-compatible) |
| Push | SSE via `SseEmitter`, virtual threads enabled |
| PDF in / out | Apache PDFBox 3.x / Thymeleaf + openhtmltopdf, embedded DejaVu Sans font |

Note: Boot 4 renamed the web starter to `spring-boot-starter-webmvc`. Do not "fix" it back.

## Commands

```bash
# backend (from backend/)
./mvnw spring-boot:run          # start on :8080
./mvnw -DskipTests package      # build jar
LLM_MODEL=... ./mvnw spring-boot:run   # model is env-configured, never hardcoded

# frontend (from frontend/)
npm run start                   # dev server on :4200, proxies /api → :8080
npm run build                   # production build
ng lint                       # linter must be clean
```

## Conventions

- Java: records for DTOs, constructor injection only (no `@Autowired` fields), packages per PLAN §6.2 (`config`, `api`, `job`, `sse`, `pipeline`, `pdf`, `llm`, `storage`).
- Angular: standalone components only, signals as the state source, new control-flow syntax (`@if/@for/@switch`), `inject()`, strict TypeScript, `OnPush`.
- No secrets in the repo. Configuration via `application.yml` + env vars (`LLM_MODEL`, `LLM_BASE_URL`).
- CV/posting text is untrusted input (prompt injection): always wrap it in delimiters before sending to the LLM; never log its content.
- Contract changes go into `docs/API.md` first, then code.
- Minor decisions: decide yourself and record one line in `docs/DECISIONS.md`. Architectural questions: ask the user.

## Milestone rule

Do not start milestone N+1 until milestone N's acceptance criterion (PLAN §9) is met. Commit per milestone with message prefix `M<n>:`.
