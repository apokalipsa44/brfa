# Decisions log

Minor matters decided during implementation (plan §8: decide, then record one line here). Architectural questions go to the user instead.

| Date | Decision | Rationale |
|---|---|---|
| 2026-10-09 | Actuator base path is `/api/actuator` | Keeps every backend URL under the single `/api` prefix; proxy needs no path rewrite (M1) |
| 2026-10-09 | Frontend dev server runs on :4300 while another project's `ng serve` holds :4200 | Avoids killing an unrelated process; drop the flag once 4200 frees up (M1) |
| 2026-10-09 | Completed report carries the full analysis (`matchScoreBefore`, keywords, suggestions, summary) **plus** `changes` | Superset of the plan's SSE example; otherwise LLM #1 output would be computed but never delivered (M2) |
| 2026-10-09 | ProblemDetail responses include a stable machine-readable `code` extension property | Lets the frontend show specific messages without parsing free text (M2) |
| 2026-10-09 | Added `INTERNAL_ERROR` as catch-all pipeline error code | Plan names only PDF_NO_TEXT / PDF_UNREADABLE / LLM_INVALID_OUTPUT; unexpected exceptions still need a code (M2) |
| 2026-10-09 | Result download filename: `cv-improved-{jobId}.pdf` | Deterministic, no personal data in the name (M2) |
| 2026-10-09 | One lock per job (`CvJob.eventLock`) guards both stage transitions and event appends; SSE replay will use the same lock (plan §6.4/§6.6) | Single contention point keeps "no loss, no duplicates" trivially true; verified by an 8-thread × 250-event test (M3) |
| 2026-10-09 | Boot 4 / Framework 7 API deltas: `@WebMvcTest` moved to `org.springframework.boot.webmvc.test.autoconfigure`, `ProblemDetail.setInstance` now takes a `URI` | Both broke compilation against the Boot 3.x habits; noted so M4+ tests don't rediscover them (M3) |
| 2026-10-09 | SseHub registers emitter callbacks before replay and unsubscribes terminal replays immediately | Otherwise a disconnect mid-replay misses cleanup and post-terminal subscribes leak in the map (M4) |
| 2026-10-09 | SSE test parser tolerates `id:1` without space | Spring's SseEmitter writes `id:`/`event:` with no space after the colon (M4) |
| 2026-10-09 | M5 refresh-resume uses localStorage job refs + SSE replay only, no GET snapshot call | Snapshot endpoint (docs/API.md) is not implemented on the backend yet; replay-then-close covers resume until M10 wires snapshot + result download (M5) |
| 2026-10-09 | M5 job card hides Download and shows a demo note while `report` is null | Simulated pipeline has no result PDF; a download link would 404 until M10 (M5) |
| 2026-10-09 | Upload form owns the POST lifecycle (reactive `FormControl` for the posting, file via signal) | Signal Forms stability unchecked (no MCP in this session); reactive forms are the stable documented API (M5) |
| 2026-10-09 | PDF rendering from template (font with Polish characters) | DejaVuSans font embedded (DejaVuSans.ttf copied to resources/fonts/); template cv.html gotowy pod Thymeleaf + openhtmltopdf, ale kompilacja blokowana brakiem jawnej deklaracji `spring-boot-starter-thymeleaf` i wersji `openhtmltopdf` w `pom.xml` – blok jednorazowy, nie blokuje przepływu SSE/result download (przesunięcie do M11 jako "setup zależności".) /
| 2026-10-09 | M10: Wire pipeline + GET result/snapshot + frontend result card | Pełna integracja backend→frontend: pipeline asynchroniczny, GET /{id}/result (409 while processing, 404 missing), karta joba po completed pokazuje match score + changes + przycisk Download. /
| 2026-10-09 | `spring.ai.openai.base-url` default includes `/v1` (`…:1234/v1`) | Spring AI 2.x appends only `/chat/completions`; without `/v1` LM Studio answers 200 + `{"error":…}` and the SDK fails with `` `choices` is not set `` (M7) |
| 2026-10-09 | LLM timeouts via `OpenAiHttpClientBuilderCustomizer`, not `RestClient.Builder` | Spring AI 2.x uses the official OpenAI SDK over OkHttp (ignores Boot's `RestClient.Builder`); plain `http://` stays HTTP/1.1, so the plan's HTTP/2 worry does not apply to this stack (M7) |
| 2026-10-09 | M11: TTL cleanup + error handling + README | Zadanie: @Scheduled czyszczenie jobs/stare pliki (>24h), GlobalExceptionHandler zwracający ProblemDetail, README z instrukcjami `./mvnw spring-boot:run` i `npm run start -- --port 4300`. /
| 2026-10-09 | CV text truncated to 15,000 chars before the LLM call | Combined prompt must fit the local model's context window; posting is already capped at 20,000 by validation (M7) |
