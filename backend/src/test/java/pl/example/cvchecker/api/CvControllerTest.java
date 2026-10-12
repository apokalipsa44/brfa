package pl.example.cvchecker.api;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import pl.example.cvchecker.pdf.PdfNoTextException;
import pl.example.cvchecker.pdf.PdfTextExtractor;
import pl.example.cvchecker.pdf.PdfUnreadableException;
import pl.example.cvchecker.storage.CvContext;
import pl.example.cvchecker.storage.CvContextStore;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Contract of the active workspace CV (docs/API.md §2): upload + extract, GET current, scan rejection. */
@WebMvcTest(CvController.class)
class CvControllerTest {

    private static final byte[] PDF = ("%PDF-1.4\n%fake-cv-content\n%%EOF\n").getBytes(StandardCharsets.UTF_8);

    @Autowired
    MockMvc mvc;

    @MockitoBean
    CvContextStore store;

    @MockitoBean
    PdfTextExtractor extractor;

    @Test
    void validUploadReturns201WithReadyStatus() throws Exception {
        when(extractor.extract(any(byte[].class))).thenReturn("Jan Kowalski, Java");
        when(store.place(anyString(), any(byte[].class), anyString()))
                .thenReturn(new CvContext("cv-1", "cv.pdf", "Jan Kowalski, Java", PDF.length,
                        Instant.now(), Path.of("/tmp/cv.pdf")));

        mvc.perform(multipart("/api/cv")
                        .file(new MockMultipartFile("file", "cv.pdf", MediaType.APPLICATION_PDF_VALUE, PDF)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("cv-1"))
                .andExpect(jsonPath("$.fileName").value("cv.pdf"))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(header().string("Location", "/api/cv/cv-1"));

        verify(store).place(anyString(), any(byte[].class), eqText());
    }

    private static String eqText() {
        return org.mockito.ArgumentMatchers.eq("Jan Kowalski, Java");
    }

    @Test
    void scannedPdfReturns422AndKeepsPreviousCv() throws Exception {
        when(extractor.extract(any(byte[].class))).thenThrow(new PdfNoTextException("scan"));

        mvc.perform(multipart("/api/cv")
                        .file(new MockMultipartFile("file", "scan.pdf", MediaType.APPLICATION_PDF_VALUE, PDF)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PDF_NO_TEXT"));

        verify(store, never()).place(anyString(), any(byte[].class), anyString());
    }

    @Test
    void unreadablePdfReturns422() throws Exception {
        when(extractor.extract(any(byte[].class))).thenThrow(new PdfUnreadableException("corrupt", new RuntimeException()));

        mvc.perform(multipart("/api/cv")
                        .file(new MockMultipartFile("file", "bad.pdf", MediaType.APPLICATION_PDF_VALUE, PDF)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PDF_UNREADABLE"));

        verify(store, never()).place(anyString(), any(byte[].class), anyString());
    }

    @Test
    void missingFileReturns400() throws Exception {
        mvc.perform(multipart("/api/cv"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_FILE"));
    }

    @Test
    void nonMultipartRequestReturns415NotInternalError() throws Exception {
        mvc.perform(post("/api/cv").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void oversizedFileReturns413() throws Exception {
        byte[] big = new byte[6 * 1024 * 1024];
        System.arraycopy("%PDF-1.4\n".getBytes(StandardCharsets.UTF_8), 0, big, 0, 9);

        mvc.perform(multipart("/api/cv")
                        .file(new MockMultipartFile("file", "big.pdf", MediaType.APPLICATION_PDF_VALUE, big)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
    }

    @Test
    void wrongContentTypeReturns415() throws Exception {
        mvc.perform(multipart("/api/cv")
                        .file(new MockMultipartFile("file", "cv.pdf", MediaType.TEXT_PLAIN_VALUE, PDF)))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("INVALID_PDF"));
    }

    @Test
    void fileWithoutPdfSignatureReturns415() throws Exception {
        mvc.perform(multipart("/api/cv")
                        .file(new MockMultipartFile("file", "cv.pdf", MediaType.APPLICATION_PDF_VALUE,
                                "this is not a pdf".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("INVALID_PDF"));
    }

    @Test
    void getCurrentReturnsMetadata() throws Exception {
        when(store.current()).thenReturn(java.util.Optional.of(
                new CvContext("cv-1", "cv.pdf", "Jan Kowalski", PDF.length, Instant.now(), Path.of("/tmp/cv.pdf"))));

        mvc.perform(get("/api/cv"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("cv-1"))
                .andExpect(jsonPath("$.fileName").value("cv.pdf"))
                .andExpect(jsonPath("$.status").value("READY"));
    }

    @Test
    void getCurrentWithoutCvReturns404() throws Exception {
        when(store.current()).thenReturn(java.util.Optional.empty());

        mvc.perform(get("/api/cv"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CV_NOT_FOUND"));
    }
}