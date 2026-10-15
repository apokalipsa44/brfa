package pl.example.cvchecker.posting;

import java.util.Optional;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads schema.org {@code JobPosting} JSON-LD from a page (docs/API.md §4.1). When present, the
 * posting text is taken directly from it — no browser render and no cleanup LLM call are needed.
 * Handles the common shapes: a bare object, an array of objects, and an {@code @graph} container.
 */
@Component
public class JsonLdExtractor {

    private final ObjectMapper mapper;

    public JsonLdExtractor(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<StructuredJobPosting> extract(String html) {
        Document document = Jsoup.parse(html);
        for (var script : document.select("script[type=application/ld+json]")) {
            String json = script.data();
            if (json == null || json.isBlank()) continue;
            Optional<StructuredJobPosting> found;
            try {
                found = extractFromNode(mapper.readTree(json));
            } catch (Exception e) {
                continue; // malformed JSON-LD block: ignore and try the next one
            }
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private Optional<StructuredJobPosting> extractFromNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                Optional<StructuredJobPosting> found = extractFromNode(child);
                if (found.isPresent()) return found;
            }
            return Optional.empty();
        }
        if (!node.isObject()) {
            return Optional.empty();
        }
        if (node.has("@graph")) {
            return extractFromNode(node.get("@graph"));
        }
        if (!isJobPosting(node.path("@type"))) {
            return Optional.empty();
        }
        String title = text(node.path("title"));
        String employer = employer(node.path("hiringOrganization"));
        String description = clean(text(node.path("description")));
        if ((description == null || description.isBlank())
                && (title == null || title.isBlank())
                && (employer == null || employer.isBlank())) {
            return Optional.empty();
        }
        return Optional.of(new StructuredJobPosting(title, employer, description));
    }

    private static boolean isJobPosting(JsonNode type) {
        if (type == null || type.isNull()) return false;
        if (type.isArray()) {
            for (JsonNode t : type) {
                if (isJobPosting(t)) return true;
            }
            return false;
        }
        String value = type.asString("");
        return value.equalsIgnoreCase("JobPosting") || value.endsWith("/JobPosting");
    }

    private static String employer(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isObject()) {
            return text(node.path("name"));
        }
        return text(node);
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull() || !node.isValueNode()) return null;
        String value = node.asString("");
        return value.isBlank() ? null : value.strip();
    }

    /** JSON-LD descriptions are HTML fragments; strip tags and collapse whitespace. */
    private static String clean(String value) {
        return value == null ? null : Jsoup.parse(value).text().strip();
    }
}
