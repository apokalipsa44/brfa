# CV Checker (core)

Upload a CV (PDF) and paste a job posting → the backend runs a local LLM (LM Studio),
checks the ATS-friendliness of the CV against the posting, rewrites it, and generates a
new PDF. Progress is pushed to the browser over SSE; the improved file is downloaded when done.

## Stack

| Layer | Technology |
|---|---|
| Frontend | Angular 22 (standalone, signals, zoneless), Tailwind CSS |
| Backend | Java 21+ (JDK 25), Spring Boot 4/Spring MVC, virtual threads |
| LLM | Spring AI 2.x → LM Studio (OpenAI-compatible, `http://localhost:1234`) |
| Push | SSE (`SseEmitter`) |
| PDF | Apache PDFBox 3.x (extraction *and* rendering; template renders with embedded DejaVuSans for Polish characters) |

## Prerequisites

- JDK 25 and Node 22+
- LM Studio running with a loaded chat/instruct model (see https://lmstudio.ai).
  `GET http://localhost:1234/v1/models` should list it.

## Configuration (environment variables)

| Variable | Default | Meaning |
|---|---|---|
| `LLM_MODEL` | `unconfigured` | model id served by LM Studio (must be set, unless the UI model dropdown is used) |
| `LLM_BASE_URL` | `http://localhost:1234/v1` | OpenAI-compatible base URL |
| `SPRING_AI_OPENAI_API_KEY` | `lm-studio` | API key placeholder for LM Studio |

Pipeline mode is set in `backend/src/main/resources/application.yml`:
`cvchecker.pipeline.mode=real` (default) runs the full PDF/LLM/PDF flow;
`=simulated` runs a staged sleeping pipeline (used by SSE integration tests).

## Run

```bash
# backend (:8080)
cd backend
LLM_MODEL=your-model-id ./mvnw spring-boot:run

# frontend (:4200, proxies /api → :8080)
cd frontend
npm install
npm run start
```

Open http://localhost:4200.

## Tests and checks

```bash
# backend (2 LLM live tests are skipped unless LM_STUDIO_LIVE=1 is set)
cd backend
./mvnw test
LM_STUDIO_LIVE=1 LLM_MODEL=your-model-id ./mvnw test   # incl. live LM Studio tests

# frontend
cd frontend
npm run lint
npm run build
npx ng test --watch=false
```

## Docs

- `PLAN.en.md` — implementation plan (scope, architecture, milestones)
- `docs/API.md` — REST/SSE contract (contract changes land here first)
- `docs/DECISIONS.md` — recorded technical decisions