package pl.example.cvchecker.api;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import pl.example.cvchecker.job.CvJob;
import pl.example.cvchecker.job.CvJobRegistry;
import pl.example.cvchecker.job.Stage;
import pl.example.cvchecker.pipeline.CvProcessingPipeline;
import pl.example.cvchecker.sse.SseHub;
import pl.example.cvchecker.storage.CvContext;
import pl.example.cvchecker.storage.CvContextStore;
import pl.example.cvchecker.storage.TempFileStorage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

    @MockitoBean
    SseHub hub;

    @MockitoBean
    CvProcessingPipeline pipeline;

    @MockitoBean
    CvContextStore cvStore;

    @Test
    void validUploadReturns202AndRegistersQueuedJob() throws Exception {
        stubStoredCv();
        when(storage.storeOriginal(anyString(), any(byte[].class))).thenReturn(Path.of("/tmp/cvchecker-test/job.pdf"));

        mvc.perform(multipart("/api/cv-jobs")
                        .param("cvId", "cv-1")
                        .param("jobDescription", "Senior Java developer wanted"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.stage").value("QUEUED"))
                .andExpect(jsonPath("$.jobId").isString());

        var captor = org.mockito.ArgumentCaptor.forClass(CvJob.class);
        org.mockito.Mockito.verify(registry).create(captor.capture());
        CvJob job = captor.getValue();
        assertThat(job.stage()).isEqualTo(Stage.QUEUED);
        assertThat(job.jobDescription()).isEqualTo("Senior Java developer wanted");
        assertThat(job.originalFile()).isEqualTo(Path.of("/tmp/cvchecker-test/job.pdf"));
    }

    @Test
    void noActiveCvReturns404() throws Exception {
        mvc.perform(multipart("/api/cv-jobs").param("jobDescription", "x"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CV_NOT_FOUND"));
    }

    @Test
    void emptyJobDescriptionReturns400() throws Exception {
        stubStoredCv();

        mvc.perform(multipart("/api/cv-jobs")
                        .param("cvId", "cv-1")
                        .param("jobDescription", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_JOB_DESCRIPTION"));
    }

    @Test
    void missingJobDescriptionReturns400() throws Exception {
        stubStoredCv();

        mvc.perform(multipart("/api/cv-jobs").param("cvId", "cv-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_JOB_DESCRIPTION"));
    }

    @Test
    void tooLongJobDescriptionReturns400() throws Exception {
        stubStoredCv();

        mvc.perform(multipart("/api/cv-jobs")
                        .param("cvId", "cv-1")
                        .param("jobDescription", "a".repeat(20_001)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("JOB_DESCRIPTION_TOO_LONG"));
    }

    /** The active CV lives on disk; jobs copy its bytes, so the controller reads the current CV. */
    private void stubStoredCv() throws Exception {
        Path cvFile = Files.createTempFile("cvchecker-test-cv", ".pdf");
        Files.write(cvFile, PDF);
        CvContext cv = new CvContext("cv-1", "cv.pdf", "Jan Kowalski", PDF.length, Instant.now(), cvFile);
        when(cvStore.current()).thenReturn(java.util.Optional.of(cv));
    }
}
