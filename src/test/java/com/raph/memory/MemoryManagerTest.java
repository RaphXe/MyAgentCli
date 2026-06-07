package com.raph.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryManagerTest {
    @TempDir
    Path tempDir;

    @Test
    void saveToMemoryReturnsStructuredResult() throws Exception {
        Path storage = tempDir.resolve("memory.json");
        MemoryManager manager = new MemoryManager(null, new LongTermHistory(storage));

        MemoryManager.SaveMemoryResult result = manager.saveToMemory("用户偏好短回答", null);

        assertTrue(result.saved());
        assertEquals("已保存 1 条记忆到长期记忆中", result.message());
        assertEquals(MemoryType.PREFERENCE, result.entry().type());
        assertEquals(1, result.totalCount());
        assertTrue(Files.exists(storage));
    }

    @Test
    void blankDescriptionIsRejectedWithoutWriting() throws Exception {
        Path storage = tempDir.resolve("memory.json");
        MemoryManager manager = new MemoryManager(null, new LongTermHistory(storage));

        MemoryManager.SaveMemoryResult result = manager.saveToMemory("   ", null);

        assertFalse(result.saved());
        assertEquals("描述为空，无法保存", result.message());
        assertEquals(0, manager.getLongTermHistory().size());
        assertFalse(Files.exists(storage));
    }
}
