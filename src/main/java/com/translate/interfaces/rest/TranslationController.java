package com.translate.interfaces.rest;

import com.translate.application.service.TranslationApplicationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Tag(name = "Translations", description = "AI-powered subtitle translation")
@RestController
@RequestMapping("/api/translations")
public class TranslationController {

    private final TranslationApplicationService translationApplicationService;

    public TranslationController(TranslationApplicationService translationApplicationService) {
        this.translationApplicationService = translationApplicationService;
    }

    @Operation(
            summary = "Translate an SRT subtitle file",
            description = "Upload an .srt file and a target language. Returns the translated .srt file as a download.",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Translated .srt file",
                            content = @Content(mediaType = "application/x-subrip")
                    )
            }
    )
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> translate(
            @Parameter(description = "The .srt subtitle file to translate", required = true)
            @RequestParam("file") MultipartFile file,

            @Parameter(description = "Target language (e.g. Albanian, Spanish, French)", required = true)
            @RequestParam("translateTo") String translateTo
    ) throws IOException {
        String translatedContent = translationApplicationService.translate(file, translateTo);

        String originalFileName = file.getOriginalFilename() != null
                ? file.getOriginalFilename()
                : "subtitle.srt";
        String outputFileName = translationApplicationService.buildOutputFileName(originalFileName, translateTo);

        byte[] responseBytes = translatedContent.getBytes(StandardCharsets.UTF_8);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + outputFileName + "\"")
                .contentType(MediaType.parseMediaType("application/x-subrip"))
                .contentLength(responseBytes.length)
                .body(responseBytes);
    }
}
