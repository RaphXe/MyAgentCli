package com.raph.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SkillRepository {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static volatile SkillRepository DEFAULT;

    private final Map<String, Skill> skills;

    public SkillRepository(List<Skill> skills) {
        Map<String, Skill> values = new LinkedHashMap<>();
        for (Skill skill : skills == null ? List.<Skill>of() : skills) {
            if (skill != null && !skill.id().isBlank()) {
                values.put(skill.id(), skill);
            }
        }
        this.skills = Map.copyOf(values);
    }

    public static SkillRepository defaultRepository() {
        SkillRepository current = DEFAULT;
        if (current == null) {
            synchronized (SkillRepository.class) {
                current = DEFAULT;
                if (current == null) {
                    current = loadDefault();
                    DEFAULT = current;
                }
            }
        }
        return current;
    }

    public static SkillRepository reloadDefault() {
        synchronized (SkillRepository.class) {
            DEFAULT = loadDefault();
            return DEFAULT;
        }
    }

    public static SkillRepository loadDefault() {
        List<Skill> loaded = new ArrayList<>();
        loaded.addAll(loadBuiltin());
        for (Path path : runtimeSkillPaths()) {
            loaded.addAll(loadDirectory(path));
        }
        return new SkillRepository(loaded);
    }

    public List<Skill> all() {
        return List.copyOf(skills.values());
    }

    public Skill find(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return skills.get(id.trim());
    }

    public List<Skill> findAll(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Skill> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String id : ids) {
            Skill skill = find(id);
            if (skill != null && seen.add(skill.id())) {
                result.add(skill);
            }
        }
        return List.copyOf(result);
    }

    public String renderSkills(List<String> ids, String title) {
        List<Skill> matches = findAll(ids);
        if (matches.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(title == null || title.isBlank() ? "Skill instructions" : title).append("\n");
        for (Skill skill : matches) {
            if (skill.isBlank()) {
                continue;
            }
            sb.append("\n## ").append(skill.id()).append("\n");
            if (!skill.description().isBlank()) {
                sb.append(skill.description()).append("\n\n");
            }
            sb.append(skill.content().trim()).append("\n");
        }
        return sb.toString().trim();
    }

    private static List<Skill> loadBuiltin() {
        List<Skill> loaded = new ArrayList<>();
        try (InputStream in = SkillRepository.class.getClassLoader()
                .getResourceAsStream("skills/builtin/manifest.json")) {
            if (in == null) {
                return List.of();
            }
            JsonNode root = MAPPER.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            JsonNode files = root.path("skills");
            if (!files.isArray()) {
                return List.of();
            }
            for (JsonNode fileNode : files) {
                String file = fileNode.asText();
                try (InputStream skillIn = SkillRepository.class.getClassLoader()
                        .getResourceAsStream("skills/builtin/" + file)) {
                    if (skillIn != null) {
                        loaded.add(parseMarkdown(
                                new String(skillIn.readAllBytes(), StandardCharsets.UTF_8),
                                "classpath:skills/builtin/" + file,
                                defaultIdFromPath(Path.of(file))
                        ));
                    }
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return List.copyOf(loaded);
    }

    private static List<Skill> loadDirectory(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return List.of();
        }
        List<Skill> loaded = new ArrayList<>();
        try (var stream = Files.walk(root, 8)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals("SKILL.md")
                            || path.getFileName().toString().endsWith(".skill.md"))
                    .sorted()
                    .forEach(path -> {
                        try {
                            String fallbackId = root.relativize(path).toString()
                                    .replace('\\', '/')
                                    .replaceAll("/?SKILL\\.md$", "")
                                    .replaceAll("\\.skill\\.md$", "");
                            loaded.add(parseMarkdown(Files.readString(path), path.toString(), fallbackId));
                        } catch (IOException ignored) {
                            // Ignore unreadable runtime skills; the agent should still start.
                        }
                    });
        } catch (IOException ignored) {
            return List.of();
        }
        return List.copyOf(loaded);
    }

    private static Skill parseMarkdown(String raw, String source, String fallbackId) {
        String text = raw == null ? "" : raw;
        Map<String, String> metadata = new LinkedHashMap<>();
        String content = text;
        if (text.startsWith("---\n") || text.startsWith("---\r\n")) {
            int start = text.indexOf('\n') + 1;
            int end = text.indexOf("\n---", start);
            if (end >= 0) {
                String header = text.substring(start, end);
                content = text.substring(text.indexOf('\n', end + 1) + 1);
                for (String line : header.split("\\R")) {
                    int colon = line.indexOf(':');
                    if (colon > 0) {
                        metadata.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
                    }
                }
            }
        }
        String id = metadata.getOrDefault("id", fallbackId);
        return new Skill(
                id,
                metadata.getOrDefault("name", id),
                metadata.getOrDefault("version", "1"),
                metadata.getOrDefault("description", ""),
                splitList(metadata.get("tags")),
                source,
                content
        );
    }

    private static List<Path> runtimeSkillPaths() {
        String configured = System.getProperty("paicli.skills.path", "");
        List<Path> paths = new ArrayList<>();
        if (!configured.isBlank()) {
            for (String part : configured.split(java.io.File.pathSeparator)) {
                if (!part.isBlank()) {
                    paths.add(Path.of(part.trim()));
                }
            }
        }
        paths.add(Path.of(".agents/skills"));
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank()) {
            paths.add(Path.of(home, ".paicli", "skills"));
        }
        return paths.stream().distinct().toList();
    }

    private static String defaultIdFromPath(Path path) {
        String value = path.toString().replace('\\', '/');
        return value.replaceAll("/?SKILL\\.md$", "").replaceAll("\\.skill\\.md$", "");
    }

    private static List<String> splitList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isBlank()) {
                result.add(trimmed);
            }
        }
        return List.copyOf(result);
    }
}
