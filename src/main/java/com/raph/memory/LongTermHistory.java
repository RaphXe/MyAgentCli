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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class LongTermHistory {

    private static final Path STORAGE_PATH = Paths.get(
            System.getProperty("user.home"), ".paicli", "long_term_memory.json");

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final List<MemoryEntry> entries;

    public LongTermHistory() {
        this.entries = new ArrayList<>();
    }

    public void load() {
        if (!Files.exists(STORAGE_PATH)) {
            return;
        }
        try {
            String json = Files.readString(STORAGE_PATH, StandardCharsets.UTF_8);
            if (json.isBlank()) return;
            List<MemoryEntry> loaded = MAPPER.readValue(json, new TypeReference<List<MemoryEntry>>() {});
            entries.clear();
            entries.addAll(loaded);
        } catch (IOException e) {
            System.err.println("加载长期记忆失败: " + e.getMessage());
        }
    }

    public void save() {
        try {
            Files.createDirectories(STORAGE_PATH.getParent());
            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(entries);
            Files.writeString(STORAGE_PATH, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("保存长期记忆失败: " + e.getMessage());
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

    public static String generateId() {
        return UUID.randomUUID().toString();
    }
}
