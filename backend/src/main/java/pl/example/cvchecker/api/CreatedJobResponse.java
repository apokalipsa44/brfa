package pl.example.cvchecker.api;

import pl.example.cvchecker.job.Stage;

public record CreatedJobResponse(String jobId, Stage stage) {
}
