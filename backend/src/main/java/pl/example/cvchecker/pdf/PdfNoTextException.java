package pl.example.cvchecker.pdf;

/** The PDF has no extractable text — most likely a scan (OCR is out of scope, plan §6.7). */
public class PdfNoTextException extends RuntimeException {

    public PdfNoTextException(String message) {
        super(message);
    }
}
