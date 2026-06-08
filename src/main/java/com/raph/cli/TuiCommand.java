package com.raph.cli;

public record TuiCommand(Type type, String arguments, String rawInput) {
    public enum Type {
        USER_INPUT,
        HELP,
        STATUS,
        TOOLS,
        LOGS,
        THEME,
        COMPACT,
        PLAN,
        TEAM,
        MCP,
        SKILLS,
        HITL,
        CONNECT,
        MODEL,
        SAVE,
        CLEAR,
        EXIT,
        UNKNOWN
    }

    public boolean hasArguments() {
        return arguments != null && !arguments.isBlank();
    }
}
