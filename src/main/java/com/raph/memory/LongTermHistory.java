package com.raph.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class LongTermHistory {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final Path storagePath;
    private final List<MemoryEntry> entries;

    public LongTermHistory() {
        this(defaultStoragePath());
    }

    public LongTermHistory(Path storagePath) {
        this.storagePath = storagePath == null ? defaultStoragePath() : storagePath.toAbsolutePath().normalize();
        this.entries = new ArrayList<>();
    }

    public LoadResult load() {
        if (!Files.exists(storagePath)) {
            entries.clear();
            return LoadResult.empty(storagePath);
        }
        try {
            String json = Files.readString(storagePath, StandardCharsets.UTF_8);
            if (json.isBlank()) {
                entries.clear();
                return LoadResult.loaded(storagePath, 0);
            }
            List<MemoryEntry> loaded = MAPPER.readValue(json, new TypeReference<List<MemoryEntry>>() {});
            entries.clear();
            entries.addAll(loaded);
            return LoadResult.loaded(storagePath, entries.size());
        } catch (IOException e) {
            entries.clear();
            Path backupPath = backupCorruptFile();
            return LoadResult.failed(storagePath, backupPath, e.getMessage());
        }
    }

    public SaveResult save() throws IOException {
        try {
            Path parent = storagePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(entries);
            writeStringAtomically(storagePath, json);
            return new SaveResult(storagePath, entries.size());
        } catch (IOException e) {
            throw new IOException("保存长期记忆失败: " + e.getMessage(), e);
        }
    }

    public void addEntry(MemoryEntry entry) {
        entries.add(entry);
    }

    public void removeEntry(String id) {
        entries.removeIf(e -> e.id().equals(id));
    }

    public List<MemoryEntry> getAllEntries() {
        return Collections.unmodifiableList(entries);
    }

    public int size() {
        return entries.size();
    }

    public Path storagePath() {
        return storagePath;
    }

    public static String generateId() {
        return UUID.randomUUID().toString();
    }

    private Path backupCorruptFile() {
        if (!Files.exists(storagePath)) {
            return null;
        }
        Path backupPath = storagePath.resolveSibling(storagePath.getFileName()
                + ".corrupt-" + System.currentTimeMillis());
        try {
            Files.move(storagePath, backupPath, StandardCopyOption.REPLACE_EXISTING);
            return backupPath;
        } catch (IOException ignored) {
            return null;
        }
    }

    private static Path defaultStoragePath() {
        return Paths.get(System.getProperty("user.home"), ".paicli", "long_term_memory.json");
    }

    private static void writeStringAtomically(Path path, String content) throws IOException {
        Path parent = path.getParent();
        if (parent == null) {
            parent = Path.of(".");
        }
        Files.createDirectories(parent);
        String prefix = path.getFileName() == null ? "memory" : path.getFileName().toString();
        if (prefix.length() < 3) {
            prefix = (prefix + "___").substring(0, 3);
        }
        Path temp = Files.createTempFile(parent, prefix, ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveFailure) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public record LoadResult(boolean success, boolean existed, int count, Path storagePath,
                             Path backupPath, String message) {
        private static LoadResult empty(Path storagePath) {
            return new LoadResult(true, false, 0, storagePath, null, "长期记忆文件不存在，已使用空记忆。");
        }

        private static LoadResult loaded(Path storagePath, int count) {
            return new LoadResult(true, true, count, storagePath, null, "已加载 " + count + " 条长期记忆。");
        }

        private static LoadResult failed(Path storagePath, Path backupPath, String error) {
            String backup = backupPath == null ? "备份失败" : "已备份到 " + backupPath;
            return new LoadResult(false, true, 0, storagePath, backupPath,
                    "长期记忆文件损坏，" + backup + "。原因: " + error);
        }
    }

    public record SaveResult(Path storagePath, int count) {}
}
