package pl.example.cvchecker.posting;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import pl.example.cvchecker.analysis.PostingSource;

/**
 * Resolves a link posting to text, walking the extraction ladder (docs/API.md §4.1):
 * <ol>
 *   <li>static fetch; schema.org {@code JobPosting} JSON-LD is trusted directly ({@code JSON_LD}, no
 *       LLM call);</li>
 *   <li>otherwise readable text from the static HTML ({@code STATIC_HTML});</li>
 *   <li>if too little text, a headless render ({@code RENDERED_HTML}) when a browser is available;</li>
 *   <li>for HTML-derived text, one cleanup LLM call that also decides whether the page is a job
 *       posting.</li>
 * </ol>
 * Too little text or "not a job posting" becomes {@code POSTING_NOT_FOUND}, telling the user to paste
 * the text instead.
 */
@Component
public class PostingResolver {

    private static final Logger log = LoggerFactory.getLogger(PostingResolver.class);

    /** Posting text is capped like pasted text (docs/API.md §4) to fit the model context window. */
    static final int MAX_POSTING_CHARS = 20_000;

    private final PostingFetchClient fetcher;
    private final JsonLdExtractor jsonLd;
    private final HtmlTextExtractor htmlText;
    private final PostingCleanupService cleanup;
    private final PostingRenderer renderer;
    private final PostingFetchProperties properties;

    public PostingResolver(PostingFetchClient fetcher, JsonLdExtractor jsonLd, HtmlTextExtractor htmlText,
            PostingCleanupService cleanup, PostingRenderer renderer, PostingFetchProperties properties) {
        this.fetcher = fetcher;
        this.jsonLd = jsonLd;
        this.htmlText = htmlText;
        this.cleanup = cleanup;
        this.renderer = renderer;
        this.properties = properties;
    }

    public ResolvedPosting resolve(String url, String modelId) throws java.io.IOException {
        FetchedPage page = fetcher.fetch(url);

        Optional<StructuredJobPosting> structured = jsonLd.extract(page.html());
        if (structured.isPresent() && hasText(structured.get().description())) {
            StructuredJobPosting posting = structured.get();
            log.info("Resolved posting from JSON-LD ({} chars)", posting.description().length());
            return capped(posting.description(), PostingSource.JSON_LD, posting.employer(), posting.title());
        }

        String text = htmlText.extract(page.html());
        PostingSource source = PostingSource.STATIC_HTML;
        if (text.length() < properties.minTextLength() && properties.browserEnabled()) {
            Optional<String> rendered = renderer.render(page.uri());
            if (rendered.isPresent()) {
                text = htmlText.extract(rendered.get());
                source = PostingSource.RENDERED_HTML;
            }
        }
        if (text.length() < properties.minTextLength()) {
            throw notFound();
        }

        PostingExtraction extraction = cleanup.cleanup(text, modelId);
        if (!extraction.jobPosting()) {
            throw notFound();
        }
        String cleaned = hasText(extraction.text()) ? extraction.text() : text;
        return capped(cleaned, source, extraction.employer(), extraction.position());
    }

    private static ResolvedPosting capped(String text, PostingSource source, String employer, String position) {
        if (text.length() > MAX_POSTING_CHARS) {
            return new ResolvedPosting(text.substring(0, MAX_POSTING_CHARS), source, true, employer, position);
        }
        return new ResolvedPosting(text, source, false, employer, position);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static PostingException notFound() {
        return new PostingException(PostingException.ErrorCode.POSTING_NOT_FOUND,
                "No usable job posting was found on that page. If it needs a login, paste the posting text"
                        + " instead.");
    }
}
