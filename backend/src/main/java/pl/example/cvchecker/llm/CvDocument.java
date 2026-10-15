package pl.example.cvchecker.llm;

import java.util.List;

/**
 * The improved CV as structured data (plan §6.7 step 3). The PDF renderer turns
 * this into an ATS-friendly single-column document; no graphics, no tables.
 */
public record CvDocument(String fullName, String headline, String email, String phone, String location,
        List<String> links, String summary, List<ExperienceEntry> experience, List<EducationEntry> education,
        List<String> skills, List<String> languages, List<String> certifications) {
}
