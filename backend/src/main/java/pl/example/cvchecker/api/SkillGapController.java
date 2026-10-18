package pl.example.cvchecker.api;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import pl.example.cvchecker.gaps.GapItem;
import pl.example.cvchecker.gaps.GapService;

/**
 * Skill gaps (docs/API.md §9): the missing requirements and nice-to-haves aggregated from every
 * analysis's evaluation, plus the user's own curated entries. Read is an aggregation + sync; the
 * user can add, rename, re-classify, annotate and remove gaps (basic CRUD).
 */
@RestController
@RequestMapping("/api/skill-gaps")
public class SkillGapController {

    private final GapService gaps;

    public SkillGapController(GapService gaps) {
        this.gaps = gaps;
    }

    @GetMapping
    public List<GapItem> list() {
        return gaps.list();
    }

    @PostMapping
    public ResponseEntity<GapItem> create(@RequestBody(required = false) CreateGapRequest body) {
        String text = body == null ? null : body.text();
        GapItem created = gaps.create(text, body == null ? null : body.kind(), body == null ? null : body.note());
        return ResponseEntity.status(201).body(created);
    }

    @PatchMapping("/{id}")
    public GapItem update(@PathVariable String id, @RequestBody(required = false) UpdateGapRequest body) {
        return gaps.update(id, body == null ? null : body.text(), body == null ? null : body.kind(),
                body == null ? null : body.status(), body == null ? null : body.note());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        gaps.delete(id);
        return ResponseEntity.noContent().build();
    }
}
