package com.translate.infrastructure.sse;

import com.translate.application.port.TranslationProgressPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Infrastructure component that manages translation jobs and streams progress
 * to clients via Server-Sent Events (SSE).
 *
 * Each job gets a UUID and a blocking event queue. When a client subscribes,
 * a virtual thread drains the queue and pushes events to the SseEmitter.
 * Because events are buffered in the queue, it doesn't matter whether the
 * client subscribes before or after processing starts.
 */
@Component
public class TranslationJobStore implements TranslationProgressPort {

    private static final Logger log = LoggerFactory.getLogger(TranslationJobStore.class);

    // ---------------------------------------------------------------------------
    // Event types
    // ---------------------------------------------------------------------------

    public sealed interface JobEvent permits ProgressEvent, DoneEvent, ErrorEvent {}

    public record ProgressEvent(int percentage, int processedBatches, int totalBatches) implements JobEvent {}

    public record DoneEvent() implements JobEvent {}

    public record ErrorEvent(String message) implements JobEvent {}

    public record TranslationResult(String content, String outputFileName) {}

    // ---------------------------------------------------------------------------
    // Job storage
    // ---------------------------------------------------------------------------

    private record JobEntry(
            BlockingQueue<JobEvent> events,
            AtomicReference<TranslationResult> result
    ) {}

    private final ConcurrentHashMap<String, JobEntry> jobs = new ConcurrentHashMap<>();

    @SuppressWarnings("resource")
    private final java.util.concurrent.ExecutorService sseExecutor =
            Executors.newVirtualThreadPerTaskExecutor();

    // ---------------------------------------------------------------------------
    // Job lifecycle
    // ---------------------------------------------------------------------------

    public String createJob() {
        String jobId = UUID.randomUUID().toString();
        jobs.put(jobId, new JobEntry(new LinkedBlockingQueue<>(), new AtomicReference<>()));
        log.debug("Created translation job {}", jobId);
        return jobId;
    }

    public void removeJob(String jobId) {
        jobs.remove(jobId);
        log.debug("Removed translation job {}", jobId);
    }

    // ---------------------------------------------------------------------------
    // TranslationProgressPort implementation
    // ---------------------------------------------------------------------------

    @Override
    public void reportProgress(String jobId, int percentage, int processedBatches, int totalBatches) {
        enqueue(jobId, new ProgressEvent(percentage, processedBatches, totalBatches));
    }

    @Override
    public void reportComplete(String jobId, String content, String outputFileName) {
        JobEntry entry = jobs.get(jobId);
        if (entry != null) {
            entry.result().set(new TranslationResult(content, outputFileName));
            enqueue(jobId, new DoneEvent());
        }
    }

    @Override
    public void reportError(String jobId, String message) {
        enqueue(jobId, new ErrorEvent(message != null ? message : "Unknown error"));
    }

    // ---------------------------------------------------------------------------
    // SSE subscription
    // ---------------------------------------------------------------------------

    /**
     * Creates an SseEmitter for the given job and starts a virtual thread that
     * drains the job's event queue and pushes each event to the emitter.
     */
    public SseEmitter subscribe(String jobId) {
        SseEmitter emitter = new SseEmitter(10 * 60 * 1000L); // 10-minute timeout

        JobEntry entry = jobs.get(jobId);
        if (entry == null) {
            emitter.completeWithError(new NoSuchElementException("Job not found: " + jobId));
            return emitter;
        }

        sseExecutor.submit(() -> drain(entry, emitter, jobId));
        return emitter;
    }

    // ---------------------------------------------------------------------------
    // Result retrieval
    // ---------------------------------------------------------------------------

    public Optional<TranslationResult> getResult(String jobId) {
        return Optional.ofNullable(jobs.get(jobId))
                .map(e -> e.result().get())
                .filter(Objects::nonNull);
    }

    // ---------------------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------------------

    private void enqueue(String jobId, JobEvent event) {
        JobEntry entry = jobs.get(jobId);
        if (entry != null) {
            entry.events().offer(event);
        }
    }

    private void drain(JobEntry entry, SseEmitter emitter, String jobId) {
        try {
            while (true) {
                JobEvent event = entry.events().poll(10, TimeUnit.MINUTES);

                if (event == null) {
                    // Timed out waiting for events
                    emitter.complete();
                    return;
                }

                switch (event) {
                    case ProgressEvent p -> emitter.send(
                            SseEmitter.event().name("progress").data(p));

                    case DoneEvent ignored -> {
                        emitter.send(SseEmitter.event().name("done").data("{}"));
                        emitter.complete();
                        return;
                    }

                    case ErrorEvent e -> {
                        emitter.send(SseEmitter.event().name("error").data(e));
                        emitter.complete();
                        return;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emitter.complete();
        } catch (IOException e) {
            log.debug("SSE client disconnected for job {}: {}", jobId, e.getMessage());
        } catch (Exception e) {
            log.warn("Unexpected error in SSE drain for job {}: {}", jobId, e.getMessage());
            emitter.completeWithError(e);
        }
    }
}
