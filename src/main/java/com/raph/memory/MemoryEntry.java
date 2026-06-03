package com.raph.memory;

import java.time.Instant;
import java.util.Map;

public record MemoryEntry(
        String id,
        String content,
        MemoryType type,
        Instant timestamp,
        Map<String, String> metadata,
        Integer tokenCount
) {}
