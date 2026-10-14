package pl.example.cvchecker.posting;

/**
 * A URL that the posting fetcher must reject (docs/API.md §4.1). The {@link ErrorCode} is one of the
 * stage error codes {@code URL_INVALID} / {@code URL_BLOCKED} / {@code FETCH_FAILED} so the pipeline
 * can record it verbatim on the FAILED analysis (retry re-runs FETCHING_POSTING).
 */
public class UrlGuardException extends RuntimeException {

    private final ErrorCode code;

    public UrlGuardException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }

    /** The subset of stage error codes the URL guard can raise. */
    public enum ErrorCode {
        URL_INVALID, URL_BLOCKED, FETCH_FAILED
    }
}
