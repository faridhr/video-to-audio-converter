package io.videoaudioconverter.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
public class VideoConverterController {

    private final Logger log = LoggerFactory.getLogger(VideoConverterController.class);

    private final ProgressTracker tracker;

    private final VideoConversionService service;

    public VideoConverterController(ProgressTracker tracker, VideoConversionService service) {
        this.tracker = tracker;
        this.service = service;
    }

    @PostMapping("/upload")
    public ResponseEntity<String> uploadVideo(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("File is empty");
        }

        String taskId = UUID.randomUUID().toString();
        tracker.init(taskId);

        Thread.startVirtualThread(() -> {
            try {
                service.processVideo(file, taskId);
            } catch (Exception e) {
                log.error("Error while processing video", e);
                tracker.fail(taskId);
            }
        });

        return ResponseEntity.accepted().body("Processing started. Task ID: " + taskId);
    }

    @GetMapping("/progress")
    public ResponseEntity<String> getProgress(@RequestParam("task-id") String taskId) {
        return ResponseEntity.ok(tracker.getProgress(taskId));
    }
}
