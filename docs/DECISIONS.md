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
