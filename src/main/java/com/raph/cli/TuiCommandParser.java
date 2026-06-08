package com.raph.cli;

import java.util.Locale;

public final class TuiCommandParser {
    private TuiCommandParser() {
    }

    public static TuiCommand parse(String input) {
        String trimmed = input == null ? "" : input.trim();
        if (trimmed.isEmpty()) {
            return new TuiCommand(TuiCommand.Type.USER_INPUT, "", trimmed);
        }
        if (!trimmed.startsWith("/")) {
            return new TuiCommand(TuiCommand.Type.USER_INPUT, trimmed, trimmed);
        }

        int separator = trimmed.indexOf(' ');
        String commandName = separator < 0 ? trimmed : trimmed.substring(0, separator);
        String arguments = separator < 0 ? "" : trimmed.substring(separator + 1).trim();

        TuiCommand.Type type = switch (commandName.toLowerCase(Locale.ROOT)) {
            case "/help", "/?" -> TuiCommand.Type.HELP;
            case "/status" -> TuiCommand.Type.STATUS;
            case "/tools" -> TuiCommand.Type.TOOLS;
            case "/logs" -> TuiCommand.Type.LOGS;
            case "/theme" -> TuiCommand.Type.THEME;
            case "/compact" -> TuiCommand.Type.COMPACT;
            case "/plan" -> TuiCommand.Type.PLAN;
            case "/team" -> TuiCommand.Type.TEAM;
            case "/mcp" -> TuiCommand.Type.MCP;
            case "/skills" -> TuiCommand.Type.SKILLS;
            case "/hitl" -> TuiCommand.Type.HITL;
            case "/connect" -> TuiCommand.Type.CONNECT;
            case "/model" -> TuiCommand.Type.MODEL;
            case "/save" -> TuiCommand.Type.SAVE;
            case "/clear" -> TuiCommand.Type.CLEAR;
            case "/exit" -> TuiCommand.Type.EXIT;
            default -> TuiCommand.Type.UNKNOWN;
        };

        return new TuiCommand(type, arguments, trimmed);
    }
}
