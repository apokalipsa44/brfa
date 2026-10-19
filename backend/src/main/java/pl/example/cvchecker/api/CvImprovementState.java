package pl.example.cvchecker.api;

import java.time.Instant;
import java.util.List;

import pl.example.cvchecker.cv.CvImprovementStage;

/**
 * The standalone CV build's current state (docs/API.md §10): the inputs of the current (or last)
 * build, its stage, and the download URL once completed.
 */
public record CvImprovementState(CvImprovementStage stage, List<String> skills, String modelId,
        Instant startedAt, Instant finishedAt, String errorCode, String errorMessage, String resultUrl) {
}
