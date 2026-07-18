package com.translate.application.service;

import com.translate.application.port.AiTranslationClient;
import com.translate.application.port.TranslationProgressPort;
import com.translate.domain.model.TranslatedEntry;
import com.translate.domain.model.TranslationEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TranslationApplicationServiceTest {

    @Mock
    AiTranslationClient aiTranslationClient;

    @Mock
    TranslationProgressPort progressPort;

    @InjectMocks
    TranslationApplicationService service;

    @Test
    void shouldTranslateSubtitleFileToAlbanian() throws IOException {
        // Given
        MockMultipartFile file = loadSrtFile("sample.srt");

        List<TranslationEntry> expectedBatch = List.of(
                new TranslationEntry("{{TRANSLATION_1}}", "Good luck."),
                new TranslationEntry("{{TRANSLATION_2}}", "Hi. I have an appointment with...")
        );

        List<TranslatedEntry> albanianTranslations = List.of(
                new TranslatedEntry("{{TRANSLATION_1}}", "Paç fat."),
                new TranslatedEntry("{{TRANSLATION_2}}", "Përshëndetje. Kam një takim me...")
        );

        when(aiTranslationClient.translate(expectedBatch, "Albanian"))
                .thenReturn(albanianTranslations);

        // When
        String result = service.translate(file, "Albanian");

        // Then — structure is preserved
        assertThat(result).contains("1");
        assertThat(result).contains("00:01:58,991 --> 00:02:00,367");
        assertThat(result).contains("2");
        assertThat(result).contains("00:03:39,216 --> 00:03:42,136");

        // Then — translations are applied
        assertThat(result).contains("Paç fat.");
        assertThat(result).contains("Përshëndetje. Kam një takim me...");

        // Then — original text is replaced
        assertThat(result).doesNotContain("Good luck.");
        assertThat(result).doesNotContain("Hi. I have an appointment with...");

        // Then — no leftover placeholders
        assertThat(result).doesNotContain("{{TRANSLATION_1}}");
        assertThat(result).doesNotContain("{{TRANSLATION_2}}");

        // Then — AI was called with exactly the right batch
        verify(aiTranslationClient).translate(expectedBatch, "Albanian");
    }

    @Test
    void shouldPreserveFullSrtStructureAfterTranslation() throws IOException {
        // Given
        MockMultipartFile file = loadSrtFile("sample.srt");

        when(aiTranslationClient.translate(
                List.of(
                        new TranslationEntry("{{TRANSLATION_1}}", "Good luck."),
                        new TranslationEntry("{{TRANSLATION_2}}", "Hi. I have an appointment with...")
                ),
                "Albanian"
        )).thenReturn(List.of(
                new TranslatedEntry("{{TRANSLATION_1}}", "Paç fat."),
                new TranslatedEntry("{{TRANSLATION_2}}", "Përshëndetje. Kam një takim me...")
        ));

        // When
        String result = service.translate(file, "Albanian");

        // Then — assert the complete expected SRT output
        String expected = """
                1
                00:01:58,991 --> 00:02:00,367
                Paç fat.

                2
                00:03:39,216 --> 00:03:42,136
                Përshëndetje. Kam një takim me...
                """;

        assertThat(result).isEqualToIgnoringNewLines(expected);
    }

    @Test
    void shouldBuildOutputFileNameWithLanguageSuffix() {
        assertThat(service.buildOutputFileName("movie.srt", "Albanian"))
                .isEqualTo("movie.Albanian.srt");
    }

    @Test
    void shouldBuildOutputFileNameWhenOriginalHasNoExtension() {
        assertThat(service.buildOutputFileName("movie", "Albanian"))
                .isEqualTo("movie.Albanian.srt");
    }

    @Test
    void shouldBuildOutputFileNameWhenOriginalIsNullOrBlank() {
        assertThat(service.buildOutputFileName(null, "Albanian"))
                .isEqualTo("translated.Albanian.srt");

        assertThat(service.buildOutputFileName("", "Albanian"))
                .isEqualTo("translated.Albanian.srt");
    }

    // -------------------------------------------------------------------------

    private MockMultipartFile loadSrtFile(String filename) throws IOException {
        try (InputStream is = getClass().getResourceAsStream("/subtitles/" + filename)) {
            if (is == null) {
                throw new IllegalStateException(
                        "Test resource not found: src/test/resources/subtitles/" + filename
                );
            }
            return new MockMultipartFile("file", filename, "text/plain", is.readAllBytes());
        }
    }
}
