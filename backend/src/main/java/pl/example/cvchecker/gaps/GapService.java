package pl.example.cvchecker.gaps;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import pl.example.cvchecker.analysis.Analysis;
import pl.example.cvchecker.analysis.AnalysisRegistry;
import pl.example.cvchecker.api.ApiException;
import pl.example.cvchecker.llm.Evaluation;
import pl.example.cvchecker.llm.Requirement;
import pl.example.cvchecker.llm.RequirementStatus;
import pl.example.cvchecker.llm.RequirementType;

/**
 * Aggregates the requirements every analysis found the CV lacks (docs/API.md §9) and curates them.
 *
 * <p>A gap is any requirement whose status is {@code MISSING} or whose type is {@code NICE} — i.e.
 * "what the CV is missing" plus "the nice-to-haves". On every {@link #list()} the currently
 * aggregated terms are synchronized into the persisted store (one derived row per normalized term),
 * so the user can then rename, annotate, change status or dismiss them; user fields are never
 * overwritten by re-aggregation. Deleted derived gaps become dismissed rows so they do not reappear.
 * {@code occurrences}/{@code sources} are recomputed live from the analyses on each read.
 */
@Service
public class GapService {

    /** Hard cap for a gap's text (defensive; the LLM terms are short). */
    static final int MAX_TEXT_CHARS = 300;

    /** Hard cap for the user note. */
    static final int MAX_NOTE_CHARS = 500;

    private final AnalysisRegistry analyses;
    private final GapRepository repository;

    public GapService(AnalysisRegistry analyses, GapRepository repository) {
        this.analyses = analyses;
        this.repository = repository;
    }

    /** Aggregated + curated gaps, missing first, then by status, occurrence count and text. */
    public synchronized List<GapItem> list() {
        Map<String, Aggregate> aggregate = aggregate();
        sync(aggregate);
        List<GapItem> items = new ArrayList<>();
        for (SkillGap gap : repository.findAll()) {
            if (gap.dismissed()) continue;
            items.add(toItem(gap, aggregate.get(gap.key())));
        }
        items.sort(ORDER);
        return items;
    }

    /** Adds a user-created gap; it is never touched by aggregation. */
    public synchronized GapItem create(String text, GapKind kind, String note) {
        String clean = requireText(text);
        Instant now = Instant.now();
        SkillGap gap = new SkillGap(UUID.randomUUID().toString(), normalize(clean), clean,
                kind == null ? GapKind.MISSING : kind, GapStatus.OPEN, cleanNote(note), true, false, now, now);
        repository.save(gap);
        return toItem(gap, aggregate().get(gap.key()));
    }

    /** Partial update of a gap's text, kind, status or note; every field is optional. */
    public synchronized GapItem update(String id, String text, GapKind kind, GapStatus status, String note) {
        SkillGap gap = find(id);
        if (text == null && kind == null && status == null && note == null) {
            throw new ApiException(422, "NO_UPDATES", "No updates",
                    "Provide at least one of 'text', 'kind', 'status' or 'note'.");
        }
        String newText = gap.text();
        String newKey = gap.key();
        if (text != null) {
            newText = requireText(text);
            newKey = normalize(newText);
        }
        SkillGap updated = new SkillGap(gap.id(), newKey, newText, kind == null ? gap.kind() : kind,
                status == null ? gap.status() : status, note == null ? gap.note() : cleanNote(note),
                gap.manual(), gap.dismissed(), gap.createdAt(), Instant.now());
        repository.save(updated);
        return toItem(updated, aggregate().get(updated.key()));
    }

    /**
     * Removes a gap (docs/API.md §9). A user-created gap is hard-deleted; a derived one is dismissed
     * so the next aggregation does not bring it back.
     */
    public synchronized void delete(String id) {
        SkillGap gap = find(id);
        if (gap.manual()) {
            repository.delete(id);
            return;
        }
        repository.save(new SkillGap(gap.id(), gap.key(), gap.text(), gap.kind(), gap.status(), gap.note(),
                false, true, gap.createdAt(), Instant.now()));
    }

    private SkillGap find(String id) {
        return repository.find(id).orElseThrow(() -> new ApiException(404, "GAP_NOT_FOUND", "Gap not found",
                "No skill gap with this id."));
    }

    /** Creates a derived row for every aggregated term that has none yet (dismissed rows count). */
    private void sync(Map<String, Aggregate> aggregate) {
        Set<String> known = new HashSet<>();
        for (SkillGap gap : repository.findAll()) known.add(gap.key());
        Instant now = Instant.now();
        for (Map.Entry<String, Aggregate> entry : aggregate.entrySet()) {
            if (known.contains(entry.getKey())) continue;
            Aggregate agg = entry.getValue();
            repository.save(new SkillGap(UUID.randomUUID().toString(), entry.getKey(), agg.text,
                    agg.missing ? GapKind.MISSING : GapKind.NICE, GapStatus.OPEN, "", false, false, now, now));
        }
    }

    /** Collects the eligible requirements of every analysis that finished evaluating. */
    private Map<String, Aggregate> aggregate() {
        Map<String, Aggregate> byKey = new LinkedHashMap<>();
        for (Analysis analysis : analyses.findAll()) {
            Evaluation evaluation = analysis.evaluation();
            if (evaluation == null || evaluation.requirements() == null) continue;
            Set<String> seen = new HashSet<>();
            for (Requirement requirement : evaluation.requirements()) {
                if (requirement == null || requirement.text() == null) continue;
                boolean missing = requirement.status() == RequirementStatus.MISSING;
                if (!missing && requirement.type() != RequirementType.NICE) continue;
                String key = normalize(requirement.text());
                if (key.isEmpty()) continue;
                Aggregate agg = byKey.computeIfAbsent(key, k -> new Aggregate());
                if (agg.text == null) agg.text = requirement.text().strip();
                if (missing) agg.missing = true;
                if (seen.add(key)) agg.count++;
                agg.sources.putIfAbsent(analysis.id(),
                        new GapItem.GapSource(analysis.id(), analysis.title()));
            }
        }
        return byKey;
    }

    private static GapItem toItem(SkillGap gap, Aggregate agg) {
        int occurrences = agg == null ? 0 : agg.count;
        List<GapItem.GapSource> sources = agg == null ? List.of() : List.copyOf(agg.sources.values());
        return new GapItem(gap.id(), gap.text(), gap.kind(), gap.status(), gap.note(),
                gap.manual() ? "MANUAL" : "DERIVED", occurrences, sources, gap.createdAt(), gap.updatedAt());
    }

    private static String requireText(String text) {
        String clean = text == null ? "" : text.strip();
        if (clean.isEmpty()) {
            throw new ApiException(400, "GAP_TEXT_BLANK", "Gap text blank", "Provide a non-blank skill gap.");
        }
        if (clean.length() > MAX_TEXT_CHARS) {
            throw new ApiException(400, "GAP_TEXT_TOO_LONG", "Gap text too long",
                    "The skill gap has " + clean.length() + " characters; the limit is " + MAX_TEXT_CHARS + ".");
        }
        return clean;
    }

    private static String cleanNote(String note) {
        if (note == null) return "";
        String clean = note.strip();
        return clean.length() > MAX_NOTE_CHARS ? clean.substring(0, MAX_NOTE_CHARS) : clean;
    }

    /** Case-insensitive, whitespace-collapsed key used to merge the same term across analyses. */
    static String normalize(String text) {
        if (text == null) return "";
        return text.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /** Mutable aggregation bucket; a term raises at most one occurrence per analysis. */
    private static final class Aggregate {
        private String text;
        private boolean missing;
        private int count;
        private final Map<String, GapItem.GapSource> sources = new LinkedHashMap<>();
    }

    private static final Comparator<GapItem> ORDER = Comparator
            .comparingInt((GapItem g) -> g.kind() == GapKind.MISSING ? 0 : 1)
            .thenComparingInt(g -> g.status().ordinal())
            .thenComparing(Comparator.comparingInt(GapItem::occurrences).reversed())
            .thenComparing(GapItem::text, String.CASE_INSENSITIVE_ORDER);
}
