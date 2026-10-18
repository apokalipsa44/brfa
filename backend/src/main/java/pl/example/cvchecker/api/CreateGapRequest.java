package pl.example.cvchecker.api;

import pl.example.cvchecker.gaps.GapKind;

/** Body of {@code POST /api/skill-gaps} (docs/API.md §9): the text is required, kind/note optional. */
public record CreateGapRequest(String text, GapKind kind, String note) {
}
