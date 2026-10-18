package pl.example.cvchecker.api;

import pl.example.cvchecker.gaps.GapKind;
import pl.example.cvchecker.gaps.GapStatus;

/**
 * Body of {@code PATCH /api/skill-gaps/{id}} (docs/API.md §9): every field is optional, at least one
 * must be present.
 */
public record UpdateGapRequest(String text, GapKind kind, GapStatus status, String note) {
}
