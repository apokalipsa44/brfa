package pl.example.cvchecker.gaps;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import pl.example.cvchecker.analysis.Analysis;
import pl.example.cvchecker.analysis.AnalysisRegistry;
import pl.example.cvchecker.api.ApiException;
import pl.example.cvchecker.llm.Evaluation;
import pl.example.cvchecker.llm.GapGroupingResult;
import pl.example.cvchecker.llm.GapGroupingService;
import pl.example.cvchecker.llm.GapImportanceResult;
import pl.example.cvchecker.llm.GapImportanceService;
import pl.example.cvchecker.llm.LlmInvalidOutputException;
import pl.example.cvchecker.llm.ModelContextService;
import pl.example.cvchecker.llm.Requirement;
import pl.example.cvchecker.llm.RequirementType;

/**
 * Grouped + ranked skill overview (docs/API.md §9). A regrouping run folds every curated gap into one
 * bag, asks the model to translate + group the phrasings into canonical skills ({@link
 * GapGroupingService}) and to rank them against the analysed postings ({@link GapImportanceService}),
 * then replaces the persisted cluster set. Runs asynchronously in the background (the scheduler) or
 * synchronously on demand ({@link #rebuildNow()}); the curated gap rows are never modified.
 */
@Service
public class SkillClusterService {

    private static final Logger log = LoggerFactory.getLogger(SkillClusterService.class);

    /** Upper bound of the model's importance score (prompts/rank-gaps.st). */
    static final int MAX_IMPORTANCE = 5;

    /** Hard cap for a manually edited cluster label. */
    static final int MAX_LABEL_CHARS = 120;

    /** Posting-context bounds for the ranking prompt (cheap, bounded input). */
    static final int MAX_CONTEXT_ANALYSES = 40;
    static final int MAX_CONTEXT_CHARS = 12000;

    private final GapService gaps;
    private final SkillClusterRepository clusters;
    private final GapGroupingService grouping;
    private final GapImportanceService importance;
    private final AnalysisRegistry analyses;
    private final ModelContextService models;
    private final ThreadPoolTaskExecutor executor;

    /** Guards a completed-regrouping replace against a concurrent read. */
    private final Object storeLock = new Object();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Instant lastRunAt;
    private volatile String lastError;

    public SkillClusterService(GapService gaps, SkillClusterRepository clusters, GapGroupingService grouping,
            GapImportanceService importance, AnalysisRegistry analyses, ModelContextService models,
            @Qualifier("skillGroupingExecutor") ThreadPoolTaskExecutor executor) {
        this.gaps = gaps;
        this.clusters = clusters;
        this.grouping = grouping;
        this.importance = importance;
        this.analyses = analyses;
        this.models = models;
        this.executor = executor;
    }

    /** The current clusters plus the job state; triggers no work. */
    public SkillClustersResponse snapshot() {
        return new SkillClustersResponse(running.get(), lastRunAt, lastError, list());
    }

    /** The persisted clusters joined with their live member gaps, most important first. */
    public List<GroupedSkill> list() {
        List<SkillCluster> current;
        synchronized (storeLock) {
            current = clusters.findAll();
        }
        Map<String, GapItem> byId = new HashMap<>();
        for (GapItem gap : gaps.list()) byId.put(gap.id(), gap);

        List<GroupedSkill> result = new ArrayList<>();
        for (SkillCluster cluster : current) {
            List<GapItem> members = cluster.memberGapIds().stream()
                    .map(byId::get).filter(Objects::nonNull).toList();
            if (members.isEmpty()) continue; // every member gap was deleted
            int occurrences = members.stream().mapToInt(GapItem::occurrences).sum();
            GapKind kind = members.stream().anyMatch(g -> g.kind() == GapKind.MISSING)
                    ? GapKind.MISSING : GapKind.NICE;
            List<GroupedSkill.Member> items = members.stream()
                    .map(g -> new GroupedSkill.Member(g.text(), g.kind(), g.occurrences())).toList();
            result.add(new GroupedSkill(cluster.id(), cluster.label(), cluster.importance(), cluster.rationale(),
                    occurrences, kind, items, cluster.updatedAt()));
        }
        result.sort(ORDER);
        return result;
    }

    /**
     * Synchronous regrouping for the manual trigger (docs/API.md §9). Returns the refreshed payload;
     * a run already in flight is reported as {@code 409 REBUILD_RUNNING}.
     */
    public SkillClustersResponse rebuildNow() {
        if (!running.compareAndSet(false, true)) {
            throw new ApiException(409, "REBUILD_RUNNING", "Regrouping in progress",
                    "Skill gaps are already being regrouped. Try again in a moment.");
        }
        try {
            runRebuild();
        } catch (LlmInvalidOutputException e) {
            throw new ApiException(502, "LLM_INVALID_OUTPUT", "Model output invalid",
                    "The model returned an invalid grouping. Check the active model and try again.");
        } finally {
            running.set(false);
        }
        return snapshot();
    }

    /** Asynchronous regrouping for the background job; false when a run is already in flight. */
    public boolean rebuildAsync() {
        if (!running.compareAndSet(false, true)) return false;
        executor.submit(() -> {
            try {
                runRebuild();
            } catch (RuntimeException e) {
                log.warn("Background skill regroup failed: {}", e.toString());
            } finally {
                running.set(false);
            }
        });
        return true;
    }

    private void runRebuild() {
        try {
            regroup();
            lastRunAt = Instant.now();
            lastError = null;
        } catch (RuntimeException e) {
            lastError = e.getMessage();
            throw e;
        }
    }

    private void regroup() {
        List<GapItem> gapItems = gaps.list();
        if (gapItems.isEmpty()) {
            replaceClusters(List.of());
            return;
        }
        List<String> terms = gapItems.stream().map(GapItem::text).distinct().toList();
        String model = models.activeModelId();
        GapGroupingResult groupingResult = grouping.group(terms, model);

        // Map each input term (normalized) to the model's canonical label; unmapped terms stay alone.
        Map<String, String> labelByTerm = new HashMap<>();
        for (GapGroupingResult.Group group : groupingResult.groups()) {
            String label = group.label() == null ? "" : group.label().strip();
            if (label.isEmpty() || group.members() == null) continue;
            for (String member : group.members()) {
                if (member == null || member.isBlank()) continue;
                labelByTerm.putIfAbsent(GapService.normalize(member), label);
            }
        }

        Map<String, List<String>> gapIdsByLabel = new LinkedHashMap<>();
        for (GapItem gap : gapItems) {
            String label = labelByTerm.getOrDefault(GapService.normalize(gap.text()), gap.text().strip());
            gapIdsByLabel.computeIfAbsent(label, k -> new ArrayList<>()).add(gap.id());
        }

        List<String> labels = new ArrayList<>(gapIdsByLabel.keySet());
        GapImportanceResult ranking = importance.rank(labels, postingContext(), model);
        Map<String, GapImportanceResult.Rank> rankByLabel = new HashMap<>();
        for (GapImportanceResult.Rank rank : ranking.skills()) {
            if (rank == null || rank.label() == null) continue;
            rankByLabel.putIfAbsent(GapService.normalize(rank.label()), rank);
        }

        Instant now = Instant.now();
        List<SkillCluster> built = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : gapIdsByLabel.entrySet()) {
            GapImportanceResult.Rank rank = rankByLabel.get(GapService.normalize(entry.getKey()));
            int importance = rank == null ? 1 : Math.max(1, Math.min(MAX_IMPORTANCE, rank.importance()));
            String rationale = rank == null || rank.rationale() == null ? "" : rank.rationale().strip();
            built.add(new SkillCluster(UUID.randomUUID().toString(), entry.getKey(), importance, rationale,
                    List.copyOf(entry.getValue()), now, now));
        }
        replaceClusters(built);
    }

    /**
     * Renames one cluster's canonical label (docs/API.md §9). The new label is persisted and survives
     * until the next regrouping run recomputes it; the curated gap rows are left untouched.
     */
    public SkillClustersResponse rename(String id, String label) {
        String clean = label == null ? "" : label.strip();
        if (clean.isEmpty()) {
            throw new ApiException(400, "CLUSTER_LABEL_BLANK", "Label blank",
                    "Provide a non-blank skill label.");
        }
        if (clean.length() > MAX_LABEL_CHARS) {
            throw new ApiException(400, "CLUSTER_LABEL_TOO_LONG", "Label too long",
                    "The label has " + clean.length() + " characters; the limit is " + MAX_LABEL_CHARS + ".");
        }
        synchronized (storeLock) {
            List<SkillCluster> all = clusters.findAll();
            if (all.stream().noneMatch(c -> c.id().equals(id))) throw notFound();
            Instant now = Instant.now();
            List<SkillCluster> updated = new ArrayList<>(all.size());
            for (SkillCluster cluster : all) {
                updated.add(cluster.id().equals(id)
                        ? new SkillCluster(cluster.id(), clean, cluster.importance(), cluster.rationale(),
                                cluster.memberGapIds(), cluster.createdAt(), now)
                        : cluster);
            }
            clusters.replaceAll(updated);
        }
        return snapshot();
    }

    /**
     * Removes one cluster and every underlying gap it grouped (docs/API.md §9): each member gap is
     * dismissed (derived) or hard-deleted (manual) through {@link GapService#delete(String)}, so the
     * curated list and the next regrouping reflect the removal.
     */
    public void delete(String id) {
        SkillCluster target;
        synchronized (storeLock) {
            List<SkillCluster> all = clusters.findAll();
            target = all.stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow(SkillClusterService::notFound);
            clusters.replaceAll(all.stream().filter(c -> !c.id().equals(id)).toList());
        }
        for (String gapId : target.memberGapIds()) {
            try {
                gaps.delete(gapId);
            } catch (ApiException e) {
                if (e.status() != 404) throw e; // a member gap already gone is fine
            }
        }
    }

    private static ApiException notFound() {
        return new ApiException(404, "CLUSTER_NOT_FOUND", "Cluster not found",
                "No grouped skill with this id.");
    }

    private void replaceClusters(List<SkillCluster> built) {
        synchronized (storeLock) {
            clusters.replaceAll(built);
        }
    }

    /** A compact, bounded view of the analysed postings' requirements for the ranking prompt. */
    private String postingContext() {
        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (Analysis analysis : analyses.findAll()) {
            Evaluation evaluation = analysis.evaluation();
            if (evaluation == null || evaluation.requirements() == null) continue;
            if (count++ >= MAX_CONTEXT_ANALYSES) break;
            String requirements = evaluation.requirements().stream()
                    .filter(r -> r != null && r.text() != null && !r.text().isBlank())
                    .filter(r -> r.type() == RequirementType.MUST || r.type() == RequirementType.NICE)
                    .map(Requirement::text)
                    .collect(Collectors.joining("; "));
            if (requirements.isBlank()) continue;
            builder.append("- ").append(analysis.title()).append(": ").append(requirements).append('\n');
            if (builder.length() >= MAX_CONTEXT_CHARS) break;
        }
        return builder.length() > MAX_CONTEXT_CHARS ? builder.substring(0, MAX_CONTEXT_CHARS) : builder.toString();
    }

    private static final Comparator<GroupedSkill> ORDER = Comparator
            .comparingInt(GroupedSkill::importance).reversed()
            .thenComparing(Comparator.comparingInt(GroupedSkill::occurrences).reversed())
            .thenComparing(GroupedSkill::label, String.CASE_INSENSITIVE_ORDER);
}
