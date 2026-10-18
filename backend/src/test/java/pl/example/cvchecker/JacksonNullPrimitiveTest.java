package pl.example.cvchecker;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import pl.example.cvchecker.llm.Suggestion;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared Jackson mapper must tolerate an explicit {@code null} for a primitive {@code boolean}
 * (docs/DECISIONS.md): local models occasionally emit {@code "needsConfirmation": null} and old
 * SQLite rows omit/nil the field, which Jackson 3 rejects by default.
 */
@SpringBootTest(properties = "cvchecker.persistence=memory")
class JacksonNullPrimitiveTest {

    @Autowired
    private ObjectMapper mapper;

    @Test
    void nullCoercesToFalseForPrimitiveBoolean() throws Exception {
        Suggestion suggestion = mapper.readValue("""
                {"section":"Skills","problem":"","proposal":"","before":"","after":"","priority":"LOW",
                 "needsConfirmation":null}
                """, Suggestion.class);

        assertThat(suggestion.needsConfirmation()).isFalse();
    }
}
