package pl.example.cvchecker.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** CV language detection that fills the {@code {{CV_LANGUAGE}}} prompt placeholder. */
class CvLanguageTest {

    @Test
    void detectsPolishFromDistinctiveLetters() {
        assertThat(CvLanguage.nameOf("Doświadczenie zawodowe: programista")).isEqualTo("Polish");
        assertThat(CvLanguage.instructionFor("Zażółć gęślą jaźń")).contains("Polish");
    }

    @Test
    void detectsPolishFromADistinctiveWord() {
        // No Polish-only letters here, so only the stopword "oraz" can identify the language.
        assertThat(CvLanguage.nameOf("Programista oraz analityk systemow")).isEqualTo("Polish");
    }

    @Test
    void defaultsToEnglish() {
        assertThat(CvLanguage.nameOf("Senior Java Developer with Spring Boot")).isEqualTo("English");
        assertThat(CvLanguage.instructionFor("Senior Java Developer")).isEqualTo("Write them in English.");
    }

    @Test
    void handlesBlankInput() {
        assertThat(CvLanguage.nameOf(null)).isEqualTo("English");
        assertThat(CvLanguage.nameOf("   ")).isEqualTo("English");
    }
}
