package pl.example.cvchecker.api;

import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import jakarta.servlet.http.HttpServletRequest;

/** Maps exceptions to RFC 9457 ProblemDetail responses (docs/API.md). */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String TYPE_BASE = "https://cvchecker.local/errors/";

    @ExceptionHandler(ApiException.class)
    ProblemDetail handleApi(ApiException ex, HttpServletRequest request) {
        return problem(ex.status(), ex.code(), ex.title(), ex.getMessage(), request);
    }

    /** Spring rejects the upload during multipart parsing when max-file-size is exceeded. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail handleMaxUpload(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        log.warn("Upload rejected: {}", ex.getMessage());
        return problem(413, "FILE_TOO_LARGE", "File too large",
                "The uploaded file exceeds the 5 MB limit.", request);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleOther(Exception ex, HttpServletRequest request) {
        // Spring MVC's own exceptions (unsupported media type, wrong method, malformed body, …) are
        // ErrorResponse instances and already carry the correct status — respect them instead of 500.
        if (ex instanceof ErrorResponse er) {
            int status = er.getStatusCode().value();
            ProblemDetail body = er.getBody();
            String title = body.getTitle() != null ? body.getTitle() : "Request rejected";
            String detail = body.getDetail() != null ? body.getDetail() : "The request could not be processed.";
            log.warn("Request rejected on {} {}: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
            return problem(status, codeFor(status), title, detail, request);
        }
        log.error("Unhandled error on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return problem(500, "INTERNAL_ERROR", "Internal error", "Something went wrong. Please try again.", request);
    }

    /** Stable machine code for framework-level rejections (docs/API.md § Errors). */
    private static String codeFor(int status) {
        return switch (status) {
            case 400 -> "BAD_REQUEST";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> "REQUEST_REJECTED";
        };
    }

    private ProblemDetail problem(int status, String code, String title, String detail, HttpServletRequest request) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(status), detail);
        pd.setTitle(title);
        pd.setType(URI.create(TYPE_BASE + code.toLowerCase().replace('_', '-')));
        pd.setInstance(URI.create(request.getRequestURI()));
        pd.setProperty("code", code);
        return pd;
    }
}
