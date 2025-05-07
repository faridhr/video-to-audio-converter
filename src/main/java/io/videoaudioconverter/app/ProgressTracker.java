package io.videoaudioconverter.app;

import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class ProgressTracker {

    private record Progress(int total, AtomicInteger completed, boolean failed, boolean done) {}

    private final ConcurrentHashMap<String, Progress> progressMap = new ConcurrentHashMap<>();

    public void init(String taskId) {
        progressMap.put(taskId, new Progress(0, new AtomicInteger(0), false, false));
    }

    public void setTotal(String taskId, int total) {
        progressMap.computeIfPresent(taskId, (k, p) -> new Progress(total, p.completed, p.failed, p.done));
    }

    public void increment(String taskId) {
        Progress progress = progressMap.get(taskId);
        if (progress != null) progress.completed.incrementAndGet();
    }

    public void fail(String taskId) {
        progressMap.computeIfPresent(taskId, (k, p) -> new Progress(p.total, p.completed, true, true));
    }

    public void complete(String taskId) {
        progressMap.computeIfPresent(taskId, (k, p) -> new Progress(p.total, p.completed, false, true));
    }

    public String getProgress(String taskId) {
        Progress progress = progressMap.get(taskId);
        if (progress == null) return "Task ID not found";

        if (progress.failed) return "Failed";
        if (progress.done) return "Completed";

        return String.format("%d / %d completed", progress.completed.get(), progress.total);
    }

}
