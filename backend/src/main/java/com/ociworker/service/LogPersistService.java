package com.ociworker.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Slf4j
@Service
public class LogPersistService {

    private static final long MAX_SIZE = 20 * 1024 * 1024; // 20MB
    private static final long TRIM_TARGET = 15 * 1024 * 1024; // trim to 15MB when exceeds
    private static final String LOG_FILE = "logs/app-ws.log";
    private static final int MAX_SEARCH_RESULT_LIMIT = 3000;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private Path logPath;

    @PostConstruct
    public void init() {
        String userDir = System.getProperty("user.dir");
        logPath = Paths.get(userDir, LOG_FILE);
        try {
            Files.createDirectories(logPath.getParent());
            if (!Files.exists(logPath)) {
                Files.createFile(logPath);
            }
        } catch (IOException e) {
            log.error("Failed to init log file: {}", e.getMessage());
        }
    }

    public void appendLog(String line) {
        lock.writeLock().lock();
        try {
            Files.writeString(logPath, line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            if (Files.size(logPath) > MAX_SIZE) {
                trimFile();
            }
        } catch (IOException e) {
            // silent
        } finally {
            lock.writeLock().unlock();
        }
    }

    public LogSearchResult searchRecent(String keyword, int limit) {
        String kw = keyword == null ? "" : keyword.trim();
        int safeLimit = Math.max(1, Math.min(MAX_SEARCH_RESULT_LIMIT, limit));
        if (kw.isEmpty()) {
            return new LogSearchResult(Collections.emptyList(), 0, false, safeLimit);
        }

        lock.readLock().lock();
        try {
            if (!Files.exists(logPath)) {
                return new LogSearchResult(Collections.emptyList(), 0, false, safeLimit);
            }
            String lowerKey = kw.toLowerCase(Locale.ROOT);
            ArrayDeque<String> recent = new ArrayDeque<>(safeLimit);
            int total = 0;
            for (String line : Files.readAllLines(logPath, StandardCharsets.UTF_8)) {
                if (!line.toLowerCase(Locale.ROOT).contains(lowerKey)) {
                    continue;
                }
                total++;
                if (recent.size() == safeLimit) {
                    recent.removeFirst();
                }
                recent.addLast(line);
            }
            return new LogSearchResult(new ArrayList<>(recent), total, total > safeLimit, safeLimit);
        } catch (IOException e) {
            log.error("Failed to search log: {}", e.getMessage());
            return new LogSearchResult(Collections.emptyList(), 0, false, safeLimit);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns the last N lines for initial WebSocket replay
     */
    public List<String> readLastLines(int maxLines) {
        lock.readLock().lock();
        try {
            if (!Files.exists(logPath)) return Collections.emptyList();
            List<String> all = Files.readAllLines(logPath, StandardCharsets.UTF_8);
            if (all.size() <= maxLines) return all;
            return new ArrayList<>(all.subList(all.size() - maxLines, all.size()));
        } catch (IOException e) {
            return Collections.emptyList();
        } finally {
            lock.readLock().unlock();
        }
    }

    private void trimFile() {
        try {
            List<String> lines = Files.readAllLines(logPath, StandardCharsets.UTF_8);
            long currentSize = Files.size(logPath);
            long toRemove = currentSize - TRIM_TARGET;
            long removed = 0;
            int startIdx = 0;
            for (int i = 0; i < lines.size() && removed < toRemove; i++) {
                removed += lines.get(i).getBytes(StandardCharsets.UTF_8).length + 1;
                startIdx = i + 1;
            }
            List<String> remaining = lines.subList(startIdx, lines.size());
            Files.writeString(logPath, String.join("\n", remaining) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.error("Failed to trim log file: {}", e.getMessage());
        }
    }

    public record LogSearchResult(List<String> records, int total, boolean limited, int limit) {
    }
}
