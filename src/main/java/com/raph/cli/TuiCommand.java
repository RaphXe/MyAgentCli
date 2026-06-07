package com.raph.cli;

public record TuiCommand(Type type, String arguments, String rawInput) {
    public enum Type {
        USER_INPUT,
        PLAN,
        TEAM,
        MCP,
        HITL,
        SAVE,
        CLEAR,
        EXIT,
        UNKNOWN
    }

    public boolean hasArguments() {
        return arguments != null && !arguments.isBlank();
    }
}
