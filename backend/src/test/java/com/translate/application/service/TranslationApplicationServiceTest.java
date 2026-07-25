package com.translate.application.service;

import com.translate.application.dto.AiProvider;
import com.translate.application.dto.FailedJobSnapshot;
import com.translate.application.port.AiTranslationClient;
import com.translate.application.port.TranslationProgressPort;
import com.translate.domain.model.SubtitleFile;
import com.translate.domain.model.TranslatedEntry;
import com.translate.domain.model.TranslationEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TranslationApplicationServiceTest {

    @Mock
    AiTranslationClient aiTranslationClient;

    @Mock
    AiTranslationClient geminiClient;

    @Mock
    TranslationProgressPort progressPort;

    TranslationApplicationService service;

    @BeforeEach
    void setUp() {
        service = new TranslationApplicationService(aiTranslationClient, geminiClient, progressPort);
    }

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

    // ═══════════════════════════════════════════════════════════════════════════
    // restartTranslation
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void shouldCompleteJobByMergingCompletedAndNewTranslationsOnRestart() throws Exception {
        // Given — first entry already translated, second entry remaining
        FailedJobSnapshot snapshot = snapshotWithFirstEntryDone();

        when(progressPort.getFailedSnapshot("job-1")).thenReturn(Optional.of(snapshot));
        when(aiTranslationClient.translate(snapshot.remainingEntries(), "Albanian")).thenReturn(List.of(
                new TranslatedEntry("{{TRANSLATION_2}}", "Përshëndetje. Kam një takim me...")
        ));

        // When
        service.restartTranslation("job-1");

        // Then — both translations applied to the file
        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(progressPort).reportComplete(eq("job-1"), contentCaptor.capture(), eq("movie.Albanian.srt"));

        String content = contentCaptor.getValue();
        assertThat(content).contains("Paç fat.");
        assertThat(content).contains("Përshëndetje. Kam një takim me...");
        assertThat(content).doesNotContain("{{TRANSLATION_1}}");
        assertThat(content).doesNotContain("{{TRANSLATION_2}}");
        assertThat(content).doesNotContain("Good luck.");
        assertThat(content).doesNotContain("Hi. I have an appointment with...");
    }

    @Test
    void shouldThrowWhenNoSnapshotExistsForRestart() {
        // Given
        when(progressPort.getFailedSnapshot("job-1")).thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> service.restartTranslation("job-1"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("job-1");

        verify(progressPort, never()).reportComplete(anyString(), anyString(), anyString());
        verify(progressPort, never()).reportError(anyString(), anyString());
    }

    @Test
    void shouldUpdateSnapshotAndReportErrorWhenBatchFailsDuringRestart() throws Exception {
        // Given — AI fails on the remaining entry during restart
        FailedJobSnapshot snapshot = snapshotWithFirstEntryDone();

        when(progressPort.getFailedSnapshot("job-1")).thenReturn(Optional.of(snapshot));
        when(aiTranslationClient.translate(snapshot.remainingEntries(), "Albanian"))
                .thenThrow(new RuntimeException("AI unavailable"));

        // When
        service.restartTranslation("job-1");

        // Then — snapshot updated with merged progress, error reported
        ArgumentCaptor<FailedJobSnapshot> snapshotCaptor = ArgumentCaptor.forClass(FailedJobSnapshot.class);
        verify(progressPort).saveFailedSnapshot(eq("job-1"), snapshotCaptor.capture());

        FailedJobSnapshot updatedSnapshot = snapshotCaptor.getValue();
        // Already-completed entry (from original snapshot) is preserved in the updated snapshot
        assertThat(updatedSnapshot.completedTranslations()).contains(
                new TranslatedEntry("{{TRANSLATION_1}}", "Paç fat.")
        );
        // Failing entry is still in remainingEntries so next restart can retry it
        assertThat(updatedSnapshot.remainingEntries()).contains(
                new TranslationEntry("{{TRANSLATION_2}}", "Hi. I have an appointment with...")
        );

        verify(progressPort).reportError(eq("job-1"), anyString());
        verify(progressPort, never()).reportComplete(anyString(), anyString(), anyString());
    }

    @Test
    void shouldOnlySendRemainingEntriesToAiOnRestart() throws Exception {
        // Given — only the second entry is remaining; AI must not be called with the first
        FailedJobSnapshot snapshot = snapshotWithFirstEntryDone();

        when(progressPort.getFailedSnapshot("job-1")).thenReturn(Optional.of(snapshot));
        when(aiTranslationClient.translate(snapshot.remainingEntries(), "Albanian")).thenReturn(List.of(
                new TranslatedEntry("{{TRANSLATION_2}}", "Përshëndetje. Kam një takim me...")
        ));

        // When
        service.restartTranslation("job-1");

        // Then — AI called only with the remaining entry, not the already-completed one
        verify(aiTranslationClient).translate(
                List.of(new TranslationEntry("{{TRANSLATION_2}}", "Hi. I have an appointment with...")),
                "Albanian"
        );
        verify(aiTranslationClient, never()).translate(
                eq(List.of(new TranslationEntry("{{TRANSLATION_1}}", "Good luck."))),
                anyString()
        );
    }

    // -------------------------------------------------------------------------

    private FailedJobSnapshot snapshotWithFirstEntryDone() throws IOException {
        SubtitleFile subtitleFile = SubtitleFile.parse("movie.srt", loadSrtContent("sample.srt"));

        List<TranslatedEntry> completed = List.of(
                new TranslatedEntry("{{TRANSLATION_1}}", "Paç fat.")
        );
        List<TranslationEntry> remaining = List.of(
                new TranslationEntry("{{TRANSLATION_2}}", "Hi. I have an appointment with...")
        );

        return new FailedJobSnapshot(subtitleFile, completed, remaining, "Albanian", "movie.srt", AiProvider.OPENAI);
    }

    private String loadSrtContent(String filename) throws IOException {
        try (InputStream is = getClass().getResourceAsStream("/subtitles/" + filename)) {
            if (is == null) {
                throw new IllegalStateException(
                        "Test resource not found: src/test/resources/subtitles/" + filename
                );
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
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
