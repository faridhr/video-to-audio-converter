package io.videoaudioconverter.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

@Service
public class VideoConversionService {

    private final String outputDir;

    private final int SEGMENT_DURATION = 30;

    private final Logger log = LoggerFactory.getLogger(VideoConversionService.class);

    private final ProgressTracker tracker;

    public VideoConversionService(@Value("${app.file.output.dir}") String outputDir, ProgressTracker tracker) {
        this.outputDir = outputDir;
        this.tracker = tracker;
    }

    public void processVideo(MultipartFile file, String taskId) throws IOException, InterruptedException, ExecutionException {
        Files.createDirectories(Path.of(outputDir));

        Path tempDir = Path.of(outputDir, "temp", taskId);
        Files.createDirectories(tempDir);

        Path videoPath = tempDir.resolve(file.getOriginalFilename());
        file.transferTo(videoPath);

        String baseFileName = getBaseFileName(file.getOriginalFilename());

        // 1. Split video
        ProcessBuilder splitBuilder = new ProcessBuilder(
                "ffmpeg", "-i", videoPath.toString(),
                "-f", "segment", "-segment_time", String.valueOf(SEGMENT_DURATION),
                "-c", "copy",
                tempDir.resolve(baseFileName + "_part_%03d.mp4").toString()
        );
        splitBuilder.inheritIO();
        Process splitProcess = splitBuilder.start();
        if (splitProcess.waitFor() != 0) throw new RuntimeException("Failed to split video");

        // 2. Convert parts in parallel
        List<Path> parts;
        try (Stream<Path> stream = Files.list(tempDir)) {
            parts = stream.filter(p -> p.getFileName().toString().startsWith(baseFileName + "_part_"))
                    .sorted()
                    .toList();
        }

        tracker.setTotal(taskId, parts.size());

        List<Path> audioParts = new ArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Path>> futures = new ArrayList<>();

            for (Path part : parts) {
                futures.add(executor.submit(() -> {
                    Path audio = convertToAudio(part, baseFileName, tempDir);
                    tracker.increment(taskId);

                    // 3. Compress audio after conversion
                    return compressAudio(audio, baseFileName, tempDir);
                }));
            }

            for (Future<Path> future : futures) {
                audioParts.add(future.get());
            }
        }

        // 4. Create list.txt for concat
        Path listFile = Path.of(outputDir, taskId + "_list.txt");
        try (BufferedWriter writer = Files.newBufferedWriter(listFile)) {
            for (Path audio : audioParts) {
                writer.write("file '" + audio.toAbsolutePath() + "'\n");
            }
        }

        // 5. Merge audio files
        String suffix = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String outputFileName = baseFileName + "_" + suffix + ".mp3";
        Path outputPath = Path.of(outputDir, outputFileName);

        ProcessBuilder mergeBuilder = new ProcessBuilder(
                "ffmpeg", "-f", "concat", "-safe", "0",
                "-i", listFile.toString(), "-c", "copy", outputPath.toString()
        );
        mergeBuilder.inheritIO();
        Process mergeProcess = mergeBuilder.start();
        if (mergeProcess.waitFor() != 0) throw new RuntimeException("Failed to merge audio files");

        log.info("Merged audio saved to: {}", outputPath);
        tracker.complete(taskId);

        // 5. Clean up temp directory
        deleteDirectory(tempDir);
    }

    private Path convertToAudio(Path videoPart, String baseFileName, Path tempDir) throws IOException, InterruptedException {
        String audioName = baseFileName + "_" + videoPart.getFileName().toString().replace(".mp4", ".mp3");
        Path audioPath = tempDir.resolve(audioName);

        ProcessBuilder builder = new ProcessBuilder(
                "ffmpeg", "-i", videoPart.toString(), "-q:a", "0", "-map", "a", audioPath.toString()
        );
        builder.inheritIO();
        Process process = builder.start();
        if (process.waitFor() != 0) throw new RuntimeException("Conversion failed for: " + videoPart);

        return audioPath;
    }

    private Path compressAudio(Path audioPath, String baseFileName, Path tempDir) throws IOException, InterruptedException {
        String compressedAudioName = baseFileName + "_" + audioPath.getFileName().toString().replace(".mp3", "_compressed.mp3");
        Path compressedAudioPath = tempDir.resolve(compressedAudioName);

        ProcessBuilder builder = new ProcessBuilder(
                "ffmpeg", "-i", audioPath.toString(), "-b:a", "128k", compressedAudioPath.toString()
        );
        builder.inheritIO();
        Process process = builder.start();
        if (process.waitFor() != 0) throw new RuntimeException("Compression failed for: " + audioPath);

        return compressedAudioPath;
    }

    private void deleteDirectory(Path path) throws IOException {
        if (Files.exists(path)) {
            try (Stream<Path> walk = Files.walk(path)) {
                walk.sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            }
        }
    }

    private String getBaseFileName(String originalFileName) {
        return originalFileName.substring(0, originalFileName.lastIndexOf("."));
    }

}
