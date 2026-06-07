package com.raph.cli;

public enum SessionMode {
    NORMAL("普通模式", "👤 你: "),
    PLAN("计划模式", "🧠 计划模式 > "),
    TEAM("团队模式", "👥 团队模式 > ");

    private final String displayName;
    private final String prompt;

    SessionMode(String displayName, String prompt) {
        this.displayName = displayName;
        this.prompt = prompt;
    }

    public String displayName() {
        return displayName;
    }

    public String prompt() {
        return prompt;
    }
}
