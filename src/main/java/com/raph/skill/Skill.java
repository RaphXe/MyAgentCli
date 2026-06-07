package com.raph.skill;

import java.util.List;

public record Skill(
        String id,
        String name,
        String version,
        String description,
        List<String> tags,
        String source,
        String content
) {
    public Skill {
        id = normalize(id);
        name = blankToDefault(name, id);
        version = blankToDefault(version, "1");
        description = blankToDefault(description, "");
        tags = tags == null ? List.of() : List.copyOf(tags);
        source = blankToDefault(source, "unknown");
        content = blankToDefault(content, "");
    }

    public boolean isBlank() {
        return content == null || content.isBlank();
    }

    static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
