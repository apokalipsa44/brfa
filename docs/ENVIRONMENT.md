# Environment (Phase 0)

Checked: **2026-10-09** on this machine. All versions verified live (Maven Central, npm registry, `start.spring.io` docs), not from memory.

## Tools

| Tool | Version found | Requirement | Status |
|---|---|---|---|
| Java JDK | OpenJDK **25.0.4.1** (`javac 25`) | Spring Boot 4.1.x: ≥ 17, ≤ 26 (docs.spring.io) | OK |
| Maven | **3.9.12** | any recent; project will use `mvnw` wrapper | OK |
| Node.js | **v26.10.0** | Angular CLI 22: `^22.22.3 \|\| ^24.15.0 \|\| >=26.0.0` | OK |
| npm | **11.19.1** | compatible with Node 26 | OK |
| Angular CLI | **22.2.2** (via `npx @angular/cli`; not installed globally) | latest stable, 22.x line per plan | OK |
| Git | **2.53.0** | any recent | OK |
| LM Studio | running on `http://localhost:1234` | server up + instruct model loaded | OK |

No tools missing or too old — nothing to install.

## Library versions (verified 2026-10-09)

| Artifact | Version chosen | Note |
|---|---|---|
| `org.springframework.boot:spring-boot-starter-parent` | **4.1.1** | latest stable on Maven Central (`4.2.0-M2` is a milestone, skipped) |
| Spring AI BOM + `spring-ai-starter-model-openai` | **2.0.1** | latest stable 2.x line; artifact name confirmed in the plan |
| `org.apache.pdfbox:pdfbox` | **3.0.8** | current release, 3.x per plan |
| `com.openhtmltopdf:openhtmltopdf-pdfbox` (+ `-slf4j`) | **1.0.10** | current release |

## LM Studio

- Server responds on `http://localhost:1234/v1/models`.
- Models available (read from the API, not guessed):

| Model id | Type | Notes |
|---|---|---|
| `ista-daslab/qwen3.8-27b-gsq-rco-gguf/qwen3.8-27b-gsq-rco-iq3_s.gguf` | instruct, 27B | **recommended default** for CV analysis/rewrite (PL+EN) |
| `ista-daslab/qwen3.8-27b-gsq-rco-gguf/qwen3.8-27b-gsq-rco-iq3_s-mtp.gguf` | instruct, 27B (MTP variant) | same model family |
| `qwen3-coder-30b-a3b-compacted-19b-256k` | coder, MoE ~19B active, 256k ctx | large context; code-oriented |
| `qwen2.5-coder-32b-instruct` / `qwen2.5-coder-14b-instruct` | coder instruct | older generation |
| `text-embedding-nomic-embed-text-v1.5` | embeddings | not needed for this project |

- **JSON schema mode: supported.** Probe with `response_format: {"type":"json_schema", ...}` returned valid JSON (`{"ok": true}`, finish_reason `stop`) in ~8 s on the 27B model. **Enabled in code as of 2026-10-09**: both analysis and rewrite services send a strict schema (`prompts/analysis.schema.json`, `prompts/rewrite.schema.json`) so LM Studio cannot emit structurally invalid JSON; validation + retry (with a corrective hint) remain as the safety net per plan §6.7.
- Model is configurable via env var `LLM_MODEL` (and `LLM_BASE_URL`), so swapping to another model/hosted API later needs no code change.

## Angular MCP (`ng mcp`)

- `npx -y @angular/cli mcp` works with CLI 22.2.2 (stable tooling in v22).
- Tools exposed: `ai_tutor`, `get_best_practices`, `search_documentation`, `list_projects`, `onpush_zoneless_migration`, `run_target`, `devserver_start/stop/wait_for_build`.
- Registered for this project via `.mcp.json` (omp loads project MCP config at startup; if the tools are not visible in a running session, restart the omp session). Until then the same server is callable directly over stdio JSON-RPC — functionally identical.

## Fonts (for PDF output)

- **DejaVu Sans** Regular + Bold present at `/usr/share/fonts/truetype/dejavu/` (`DejaVuSans.ttf`, `DejaVuSans-Bold.ttf`). Full Polish character set (`ąęłńóśźż`), free license allowing redistribution → will be copied into `backend/src/main/resources/fonts/` and registered with openhtmltopdf.

## Decisions (confirmed by user, 2026-10-09)

| # | Decision | Answer |
|---|---|---|
| 1 | Improved CV format | New PDF from the ATS template (original look not preserved) |
| 2 | Build tool | Maven + `mvnw` wrapper |
| 3 | Default LLM model | `ista-daslab/qwen3.8-27b-gsq-rco-gguf/qwen3.8-27b-gsq-rco-iq3_s.gguf` (env var `LLM_MODEL`) |
| 4 | Dominant CV language | English primarily (Polish character support still required in the PDF font) |
| 5 | Completion report | Show match score + changes list on the job card, not just the file |

## Dev environment (M1)

- Backend runs on **:8080**; actuator base path is **`/api/actuator`** so every backend URL lives under the single `/api` prefix used by the plan's API contract.
- Frontend dev server: port 4200 was occupied by another project's `ng serve`, so this app runs on **:4300** (`npm run start -- --port 4300`). If 4200 frees up, drop the flag and it returns to the default.
- `frontend/proxy.conf.json` routes `/api/*` → `http://localhost:8080/api/*` (no path rewrite). Verified: `GET http://localhost:4300/api/actuator/health` → `{"status":"UP"}`.
- ESLint added via `@angular-eslint/schematics` 22.5.0 (`npm run lint`).
