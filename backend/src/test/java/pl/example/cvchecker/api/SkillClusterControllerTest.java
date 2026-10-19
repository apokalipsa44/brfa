package pl.example.cvchecker.api;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import pl.example.cvchecker.gaps.GapKind;
import pl.example.cvchecker.gaps.GroupedSkill;
import pl.example.cvchecker.gaps.SkillClusterService;
import pl.example.cvchecker.gaps.SkillClustersResponse;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Contract of the grouped-skills endpoints (docs/API.md §9). */
@WebMvcTest(SkillClusterController.class)
class SkillClusterControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    SkillClusterService clusters;

    @Test
    void listReturnsClustersAndJobState() throws Exception {
        when(clusters.snapshot()).thenReturn(new SkillClustersResponse(false, null, null, List.of(
                new GroupedSkill("c1", "Docker & Kubernetes", 5, "must-have", 3, GapKind.MISSING,
                        List.of(new GroupedSkill.Member("Docker and Kubernetes", GapKind.MISSING, 2)),
                        Instant.parse("2026-10-18T10:00:00Z")))));

        mvc.perform(get("/api/skill-gaps/clusters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.running").value(false))
                .andExpect(jsonPath("$.clusters.length()").value(1))
                .andExpect(jsonPath("$.clusters[0].label").value("Docker & Kubernetes"))
                .andExpect(jsonPath("$.clusters[0].importance").value(5))
                .andExpect(jsonPath("$.clusters[0].occurrences").value(3))
                .andExpect(jsonPath("$.clusters[0].kind").value("MISSING"))
                .andExpect(jsonPath("$.clusters[0].members[0].text").value("Docker and Kubernetes"));
    }

    @Test
    void rebuildReturnsRefreshedPayload() throws Exception {
        when(clusters.rebuildNow()).thenReturn(new SkillClustersResponse(false,
                Instant.parse("2026-10-18T10:00:00Z"), null, List.of()));

        mvc.perform(post("/api/skill-gaps/clusters/rebuild"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastRunAt").value("2026-10-18T10:00:00Z"))
                .andExpect(jsonPath("$.clusters.length()").value(0));
    }

    @Test
    void rebuildWhileRunningReturns409() throws Exception {
        when(clusters.rebuildNow()).thenThrow(
                new ApiException(409, "REBUILD_RUNNING", "Regrouping in progress", "already running"));

        mvc.perform(post("/api/skill-gaps/clusters/rebuild"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REBUILD_RUNNING"));
    }

    @Test
    void renameReturnsRefreshedPayload() throws Exception {
        when(clusters.rename("c1", "Cloud & Kubernetes")).thenReturn(new SkillClustersResponse(false,
                Instant.parse("2026-10-18T10:00:00Z"), null, List.of(
                        new GroupedSkill("c1", "Cloud & Kubernetes", 5, "must-have", 3, GapKind.MISSING,
                                List.of(), Instant.parse("2026-10-18T10:00:00Z")))));

        mvc.perform(patch("/api/skill-gaps/clusters/c1")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"Cloud & Kubernetes\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clusters[0].label").value("Cloud & Kubernetes"));
    }

    @Test
    void renameWithABlankLabelReturns400() throws Exception {
        when(clusters.rename("c1", "")).thenThrow(
                new ApiException(400, "CLUSTER_LABEL_BLANK", "Label blank", "non-blank"));

        mvc.perform(patch("/api/skill-gaps/clusters/c1")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CLUSTER_LABEL_BLANK"));
    }

    @Test
    void deleteReturns204() throws Exception {
        mvc.perform(delete("/api/skill-gaps/clusters/c1"))
                .andExpect(status().isNoContent());

        verify(clusters).delete("c1");
    }

    @Test
    void deleteUnknownClusterReturns404() throws Exception {
        org.mockito.Mockito.doThrow(new ApiException(404, "CLUSTER_NOT_FOUND", "Cluster not found", "gone"))
                .when(clusters).delete("nope");

        mvc.perform(delete("/api/skill-gaps/clusters/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLUSTER_NOT_FOUND"));
    }
}
