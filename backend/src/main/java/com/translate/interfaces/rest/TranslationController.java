package com.translate.interfaces.rest;

import com.translate.application.dto.AiProvider;
import com.translate.application.service.TranslationApplicationService;
import com.translate.infrastructure.sse.TranslationJobStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Tag(name = "Translations", description = "AI-powered subtitle translation")
@RestController
@RequestMapping("/api/translations")
public class TranslationController {

    private final TranslationApplicationService translationApplicationService;
    private final TranslationJobStore jobStore;

    public TranslationController(TranslationApplicationService translationApplicationService,
                                  TranslationJobStore jobStore) {
        this.translationApplicationService = translationApplicationService;
        this.jobStore = jobStore;
    }

    // -------------------------------------------------------------------------
    // POST /api/translations — start async job, return jobId immediately
    // -------------------------------------------------------------------------

    @Operation(
            summary = "Start a subtitle translation job",
            description = "Upload an .srt file and a target language. Returns a jobId immediately. " +
                    "Subscribe to /{jobId}/events for live progress, then download via /{jobId}/download.",
            responses = {
                    @ApiResponse(responseCode = "202", description = "Job accepted, jobId returned")
            }
    )
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> startTranslation(
            @Parameter(description = "The .srt subtitle file to translate", required = true)
            @RequestParam("file") MultipartFile file,

            @Parameter(description = "Target language (e.g. Albanian, Spanish, French)", required = true)
            @RequestParam("translateTo") String translateTo,

            @Parameter(description = "AI provider to use for translation (OPENAI or GEMINI)", required = true)
            @RequestParam("aiProvider") AiProvider aiProvider
    ) throws IOException {
        String jobId = jobStore.createJob();

        byte[] fileBytes = file.getBytes();
        String originalFileName = file.getOriginalFilename() != null
                ? file.getOriginalFilename()
                : "subtitle.srt";

        translationApplicationService.translateAsync(jobId, fileBytes, originalFileName, translateTo, aiProvider);

        return ResponseEntity.accepted().body(Map.of("jobId", jobId));
    }

    // -------------------------------------------------------------------------
    // POST /api/translations/{jobId}/restart — resume a failed job
    // -------------------------------------------------------------------------

    @Operation(
            summary = "Restart a failed translation job",
            description = "Resumes processing from the last successfully completed batch. " +
                    "Only valid for jobs that failed with a saved snapshot. " +
                    "Re-subscribe to /{jobId}/events after calling this.",
            responses = {
                    @ApiResponse(responseCode = "202", description = "Restart accepted"),
                    @ApiResponse(responseCode = "404", description = "Job not found or no restart snapshot available")
            }
    )
    @PostMapping("/{jobId}/restart")
    public ResponseEntity<Map<String, String>> restart(@PathVariable String jobId) {
        if (jobStore.getFailedSnapshot(jobId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        jobStore.resetJob(jobId);
        translationApplicationService.restartTranslation(jobId);

        return ResponseEntity.accepted().body(Map.of("jobId", jobId));
    }

    // -------------------------------------------------------------------------
    // GET /api/translations/{jobId}/events — SSE progress stream
    // -------------------------------------------------------------------------

    @Operation(
            summary = "Subscribe to translation progress via SSE",
            description = "Returns a Server-Sent Events stream with 'progress', 'done', and 'error' event types."
    )
    @GetMapping(value = "/{jobId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String jobId) {
        return jobStore.subscribe(jobId);
    }

    // -------------------------------------------------------------------------
    // GET /api/translations/{jobId}/partial — download partial result on failure
    // -------------------------------------------------------------------------

    @Operation(
            summary = "Download partial results after a failed job",
            description = "Returns a ZIP archive containing two .srt files: " +
                    "one with the completed (translated) subtitle blocks, " +
                    "and one with the remaining (untranslated) subtitle blocks in their original language. " +
                    "Available after a job has failed and a snapshot has been saved.",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "ZIP archive with completed and remaining .srt files",
                            content = @Content(mediaType = "application/zip")
                    ),
                    @ApiResponse(responseCode = "404", description = "Job not found or no partial result available"),
                    @ApiResponse(responseCode = "500", description = "Failed to build ZIP archive")
            }
    )
    @GetMapping("/{jobId}/partial")
    public ResponseEntity<byte[]> downloadPartial(@PathVariable String jobId) {
        var snapshotOpt = jobStore.getFailedSnapshot(jobId)
                .filter(s -> s.completedSrtContent() != null && s.remainingSrtContent() != null);

        if (snapshotOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
        }

        var snapshot = snapshotOpt.get();
        byte[] completedBytes = snapshot.completedSrtContent().getBytes(StandardCharsets.UTF_8);
        byte[] remainingBytes = snapshot.remainingSrtContent().getBytes(StandardCharsets.UTF_8);

        byte[] zipBytes;
        try {
            zipBytes = buildZip(snapshot.completedFileName(), completedBytes,
                    snapshot.remainingFileName(), remainingBytes);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }

        String baseName = snapshot.originalFileName() != null
                ? snapshot.originalFileName().replaceFirst("\\.[^.]+$", "")
                : "subtitle";
        String zipFileName = baseName + "." + snapshot.targetLanguage() + ".partial.zip";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + zipFileName + "\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .contentLength(zipBytes.length)
                .body(zipBytes);
    }

    private static byte[] buildZip(String name1, byte[] bytes1, String name2, byte[] bytes2) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(baos)) {
            zip.putNextEntry(new ZipEntry(name1));
            zip.write(bytes1);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(name2));
            zip.write(bytes2);
            zip.closeEntry();
        }
        return baos.toByteArray();
    }

    // -------------------------------------------------------------------------
    // GET /api/translations/{jobId}/download — retrieve the translated file
    // -------------------------------------------------------------------------

    @Operation(
            summary = "Download the translated .srt file",
            description = "Returns the translated file once translation is complete. " +
                    "The job is removed from memory after the file is retrieved.",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Translated .srt file",
                            content = @Content(mediaType = "application/x-subrip")
                    ),
                    @ApiResponse(responseCode = "404", description = "Job not found or not yet complete")
            }
    )
    @GetMapping("/{jobId}/download")
    public ResponseEntity<byte[]> download(@PathVariable String jobId) {
        return jobStore.getResult(jobId)
                .map(result -> {
                    jobStore.removeJob(jobId);

                    byte[] responseBytes = result.content().getBytes(StandardCharsets.UTF_8);

                    return ResponseEntity.ok()
                            .header(HttpHeaders.CONTENT_DISPOSITION,
                                    "attachment; filename=\"" + result.outputFileName() + "\"")
                            .contentType(MediaType.parseMediaType("application/x-subrip"))
                            .contentLength(responseBytes.length)
                            .body(responseBytes);
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
