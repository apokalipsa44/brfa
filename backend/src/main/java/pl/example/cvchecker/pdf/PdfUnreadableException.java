package pl.example.cvchecker.pdf;

/** The PDF is encrypted or corrupted and cannot be read (plan §6.7). */
public class PdfUnreadableException extends RuntimeException {

    public PdfUnreadableException(String message, Throwable cause) {
        super(message, cause);
    }
}
