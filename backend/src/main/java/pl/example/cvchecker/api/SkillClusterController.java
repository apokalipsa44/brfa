package pl.example.cvchecker.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import pl.example.cvchecker.gaps.SkillClusterService;
import pl.example.cvchecker.gaps.SkillClustersResponse;

/**
 * Grouped + ranked skills (docs/API.md §9): the canonical skills folded from all gap phrasings and
 * scored against the analysed postings. Read returns the current clusters; {@code /rebuild} runs the
 * regrouping synchronously and returns the refreshed payload. A cluster can be renamed (label only) or
 * removed — removing it also dismisses/deletes every gap it grouped.
 */
@RestController
@RequestMapping("/api/skill-gaps/clusters")
public class SkillClusterController {

    private final SkillClusterService clusters;

    public SkillClusterController(SkillClusterService clusters) {
        this.clusters = clusters;
    }

    @GetMapping
    public SkillClustersResponse list() {
        return clusters.snapshot();
    }

    @PostMapping("/rebuild")
    public SkillClustersResponse rebuild() {
        return clusters.rebuildNow();
    }

    @PatchMapping("/{id}")
    public SkillClustersResponse rename(@PathVariable String id,
            @RequestBody(required = false) UpdateClusterRequest body) {
        return clusters.rename(id, body == null ? null : body.label());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        clusters.delete(id);
        return ResponseEntity.noContent().build();
    }
}
