package pl.example.cvchecker.job;

public enum Stage {
    QUEUED, EXTRACTING_TEXT, ANALYZING, REWRITING, GENERATING_PDF, COMPLETED, FAILED
}
