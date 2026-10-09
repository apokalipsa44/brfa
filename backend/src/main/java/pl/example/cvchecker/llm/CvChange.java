package pl.example.cvchecker.llm;

/** One visible edit applied to the CV, shown to the user on the job card (plan §6.7 step 3). */
public record CvChange(String section, String before, String after, String reason) {
}
