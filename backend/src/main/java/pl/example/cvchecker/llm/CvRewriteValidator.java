package pl.example.cvchecker.llm;

/**
 * Validation for the structured CV output shared by the two LLM steps that emit a
 * {@link CvRewriteResult}: the analysis rewrite (docs/API.md §4) and the standalone CV builder
 * (§10). Rejects empty documents so bad JSON never reaches the PDF renderer.
 */
final class CvRewriteValidator {

    private CvRewriteValidator() {
    }

    /** @throws LlmInvalidOutputException when the document has no name or no usable content */
    static void validate(CvRewriteResult result) {
        if (result == null || result.document() == null || result.changes() == null) {
            throw new LlmInvalidOutputException("Rewrite failed validation (null document/changes).", null);
        }
        CvDocument document = result.document();
        boolean hasContent = (document.experience() != null && !document.experience().isEmpty())
                || (document.skills() != null && !document.skills().isEmpty())
                || (document.education() != null && !document.education().isEmpty());
        if (document.fullName() == null || document.fullName().isBlank() || !hasContent) {
            throw new LlmInvalidOutputException("Rewrite failed validation (empty name/content).", null);
        }
    }
}
