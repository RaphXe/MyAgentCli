package com.raph.skill;

import java.util.ArrayList;
import java.util.List;

public final class SkillPrompts {
    private SkillPrompts() {}

    public static String system(String baseSkillId, String fallback, String... extraSkillIds) {
        List<String> ids = new ArrayList<>();
        if (baseSkillId != null && !baseSkillId.isBlank()) {
            ids.add(baseSkillId);
        }
        if (extraSkillIds != null) {
            for (String id : extraSkillIds) {
                if (id != null && !id.isBlank()) {
                    ids.add(id);
                }
            }
        }
        String rendered = SkillRepository.defaultRepository().renderSkills(ids, "");
        if (rendered == null || rendered.isBlank()) {
            return fallback == null ? "" : fallback;
        }
        return rendered;
    }

    public static String addendum(String skillId, String basePrompt) {
        String base = basePrompt == null ? "" : basePrompt;
        String rendered = SkillRepository.defaultRepository().renderSkills(List.of(skillId), "Skill addendum");
        if (rendered == null || rendered.isBlank()) {
            return base;
        }
        return base + "\n\n" + rendered;
    }
}
