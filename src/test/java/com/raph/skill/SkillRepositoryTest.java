package com.raph.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillRepositoryTest {
    @Test
    void loadsBuiltinSkillsFromClasspathManifest() {
        SkillRepository repository = SkillRepository.loadDefault();

        Skill coreAgent = repository.find("core/agent");

        assertNotNull(coreAgent);
        assertTrue(coreAgent.content().contains("智能编程助手"), coreAgent.content());
    }
}
