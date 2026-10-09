package pl.example.cvchecker.api;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import pl.example.cvchecker.job.CvJob;
import pl.example.cvchecker.job.CvJobRegistry;
import pl.example.cvchecker.job.Stage;
import pl.example.cvchecker.storage.TempFileStorage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CvJobController.class)
class CvJobControllerTest {

    private static final byte[] PDF = ("%PDF-1.4\n%fake-cv-content\n%%EOF\n").getBytes(StandardCharsets.UTF_8);

    @Autowired
    MockMvc mvc;

    @MockitoBean
    CvJobRegistry registry;

    @MockitoBean
    TempFileStorage storage;

    @Test
    void validUploadReturns202AndRegistersQueuedJob() throws Exception {
        when(storage.storeOriginal(anyString(), any(byte[].class))).thenReturn(Path.of("/tmp/cvchecker-test/job.pdf"));

        mvc.perform(multipart("/api/cv-jobs")
                        .file(new MockMultipartFile("file", "cv.pdf", "application/pdf", PDF))
                        .param("jobDescription", "Senior Java developer wanted"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.stage").value("QUEUED"))
                .andExpect(jsonPath("$.jobId").isString());

        var captor = org.mockito.ArgumentCaptor.forClass(CvJob.class);
        verify(registry).create(captor.capture());
        CvJob job = captor.getValue();
        assertThat(job.stage()).isEqualTo(Stage.QUEUED);
        assertThat(job.jobDescription()).isEqualTo("Senior Java developer wanted");
        assertThat(job.originalFile()).isEqualTo(Path.of("/tmp/cvchecker-test/job.pdf"));
    }

    @Test
    void missingFileReturns400() throws Exception {
        mvc.perform(multipart("/api/cv-jobs").param("jobDescription", "x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_FILE"));
    }

    @Test
    void oversizedFileReturns413() throws Exception {
        byte[] big = new byte[6 * 1024 * 1024];
        System.arraycopy("%PDF-1.4\n".getBytes(StandardCharsets.UTF_8), 0, big, 0, 9);

        mvc.perform(multipart("/api/cv-jobs")
                        .file(new MockMultipartFile("file", "big.pdf", "application/pdf", big))
                        .param("jobDescription", "x"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
    }

    @Test
    void fileWithoutPdfSignatureReturns415() throws Exception {
        mvc.perform(multipart("/api/cv-jobs")
                        .file(new MockMultipartFile("file", "cv.pdf", "application/pdf",
                                "this is not a pdf".getBytes(StandardCharsets.UTF_8)))
                        .param("jobDescription", "x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("INVALID_PDF"));
    }

    @Test
    void wrongContentTypeReturns415() throws Exception {
        mvc.perform(multipart("/api/cv-jobs")
                        .file(new MockMultipartFile("file", "cv.pdf", "text/plain", PDF))
                        .param("jobDescription", "x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("INVALID_PDF"));
    }

    @Test
    void emptyJobDescriptionReturns400() throws Exception {
        mvc.perform(multipart("/api/cv-jobs")
                        .file(new MockMultipartFile("file", "cv.pdf", "application/pdf", PDF))
                        .param("jobDescription", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_JOB_DESCRIPTION"));
    }

    @Test
    void missingJobDescriptionReturns400() throws Exception {
        mvc.perform(multipart("/api/cv-jobs")
                        .file(new MockMultipartFile("file", "cv.pdf", "application/pdf", PDF)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_JOB_DESCRIPTION"));
    }

    @Test
    void tooLongJobDescriptionReturns400() throws Exception {
        mvc.perform(multipart("/api/cv-jobs")
                        .file(new MockMultipartFile("file", "cv.pdf", "application/pdf", PDF))
                        .param("jobDescription", "a".repeat(20_001)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("JOB_DESCRIPTION_TOO_LONG"));
    }
}
