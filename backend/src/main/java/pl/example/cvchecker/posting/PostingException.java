package pl.example.cvchecker.posting;

/**
 * A failure while resolving a link posting (docs/API.md §4.1). The {@link ErrorCode} is one of the
 * stage error codes so the pipeline records it verbatim on the FAILED analysis (retry re-runs
 * FETCHING_POSTING).
 */
public class PostingException extends RuntimeException {

    private final ErrorCode code;

    public PostingException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public PostingException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }

    /** The stage error codes the posting fetcher can raise. */
    public enum ErrorCode {
        URL_INVALID, URL_BLOCKED, FETCH_FAILED, FETCH_NOT_HTML, POSTING_NOT_FOUND
    }
}
