package pl.example.cvchecker.api;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import pl.example.cvchecker.gaps.GapItem;
import pl.example.cvchecker.gaps.GapKind;
import pl.example.cvchecker.gaps.GapService;
import pl.example.cvchecker.gaps.GapStatus;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Contract of the skill-gap endpoints (docs/API.md §9). */
@WebMvcTest(SkillGapController.class)
class SkillGapControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    GapService gaps;

    @Test
    void listReturnsItems() throws Exception {
        when(gaps.list()).thenReturn(List.of(item("g1", "Kubernetes", GapKind.MISSING, 2)));

        mvc.perform(get("/api/skill-gaps"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("g1"))
                .andExpect(jsonPath("$[0].text").value("Kubernetes"))
                .andExpect(jsonPath("$[0].kind").value("MISSING"))
                .andExpect(jsonPath("$[0].status").value("OPEN"))
                .andExpect(jsonPath("$[0].origin").value("DERIVED"))
                .andExpect(jsonPath("$[0].occurrences").value(2));
    }

    @Test
    void createReturns201() throws Exception {
        when(gaps.create(eq("GraphQL"), isNull(), isNull())).thenReturn(new GapItem("g2", "GraphQL", GapKind.NICE,
                GapStatus.OPEN, "", "MANUAL", 0, List.of(), Instant.now(), Instant.now()));

        mvc.perform(post("/api/skill-gaps").contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"GraphQL\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.text").value("GraphQL"))
                .andExpect(jsonPath("$.origin").value("MANUAL"));
    }

    @Test
    void patchReturnsUpdatedItem() throws Exception {
        when(gaps.update("g1", null, null, GapStatus.LEARNED, "done"))
                .thenReturn(new GapItem("g1", "Kubernetes", GapKind.MISSING, GapStatus.LEARNED, "done",
                        "DERIVED", 1, List.of(), Instant.now(), Instant.now()));

        mvc.perform(patch("/api/skill-gaps/g1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"LEARNED\",\"note\":\"done\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LEARNED"))
                .andExpect(jsonPath("$.note").value("done"));
    }

    @Test
    void deleteReturns204() throws Exception {
        mvc.perform(delete("/api/skill-gaps/g1")).andExpect(status().isNoContent());
        verify(gaps).delete("g1");
    }

    @Test
    void unknownIdReturns404() throws Exception {
        org.mockito.Mockito.doThrow(new ApiException(404, "GAP_NOT_FOUND", "Gap not found", "no"))
                .when(gaps).delete("nope");

        mvc.perform(delete("/api/skill-gaps/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GAP_NOT_FOUND"));
    }

    private static GapItem item(String id, String text, GapKind kind, int occurrences) {
        return new GapItem(id, text, kind, GapStatus.OPEN, "", "DERIVED", occurrences, List.of(),
                Instant.now(), Instant.now());
    }
}
