package pl.example.cvchecker.llm;

import java.util.List;

/** Structured output of LLM #2 (rewrite.st): the improved CV plus its changelog. */
public record CvRewriteResult(CvDocument document, List<CvChange> changes) {
}
