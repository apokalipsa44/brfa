package pl.example.cvchecker.llm;

import java.util.List;

/** One entry of the experience section: a position with ATS-friendly bullet points. */
public record ExperienceEntry(String title, String company, String dates, List<String> bullets) {
}
