package com.raph.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LongTermHistoryTest {
    @TempDir
    Path tempDir;

    @Test
    void saveWritesAtomicallyReadableJson() throws Exception {
        Path storage = tempDir.resolve("memory.json");
        LongTermHistory history = new LongTermHistory(storage);
        history.addEntry(new MemoryEntry(
                "mem-1",
                "用户喜欢简洁回答",
                MemoryType.PREFERENCE,
                Instant.parse("2026-06-07T00:00:00Z"),
                Map.of("source", "test"),
                8
        ));

        LongTermHistory.SaveResult saveResult = history.save();
        LongTermHistory loaded = new LongTermHistory(storage);
        LongTermHistory.LoadResult loadResult = loaded.load();

        assertEquals(storage.toAbsolutePath().normalize(), saveResult.storagePath());
        assertTrue(loadResult.success(), loadResult.message());
        assertEquals(1, loaded.size());
        assertEquals("用户喜欢简洁回答", loaded.getAllEntries().get(0).content());
        assertTrue(Files.exists(storage));
    }

    @Test
    void loadBacksUpCorruptJsonAndStartsEmpty() throws Exception {
        Path storage = tempDir.resolve("memory.json");
        Files.writeString(storage, "{not-json", StandardCharsets.UTF_8);
        LongTermHistory history = new LongTermHistory(storage);

        LongTermHistory.LoadResult result = history.load();

        assertFalse(result.success());
        assertEquals(0, history.size());
        assertNotNull(result.backupPath(), result.message());
        assertTrue(Files.exists(result.backupPath()), result.message());
        assertFalse(Files.exists(storage));
        assertTrue(Files.readString(result.backupPath()).contains("{not-json"));
    }
}
