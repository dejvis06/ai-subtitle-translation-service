package com.translate.interfaces.rest;

import com.translate.application.service.TranslationApplicationService;
import com.translate.infrastructure.sse.TranslationJobStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
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
            @RequestParam("translateTo") String translateTo
    ) throws IOException {
        String jobId = jobStore.createJob();

        byte[] fileBytes = file.getBytes();
        String originalFileName = file.getOriginalFilename() != null
                ? file.getOriginalFilename()
                : "subtitle.srt";

        translationApplicationService.translateAsync(jobId, fileBytes, originalFileName, translateTo);

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
