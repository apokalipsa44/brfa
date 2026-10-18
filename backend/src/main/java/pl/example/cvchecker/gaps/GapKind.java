package pl.example.cvchecker.gaps;

/**
 * Whether an aggregated skill gap is something the CV lacks entirely (a MISSING requirement) or
 * something the posting merely marks as nice to have (docs/API.md §9).
 */
public enum GapKind {
    MISSING, NICE
}
