package com.raph.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TuiCommandParserTest {
    @Test
    void plainTextIsUserInput() {
        TuiCommand command = TuiCommandParser.parse("  帮我看看项目结构  ");

        assertEquals(TuiCommand.Type.USER_INPUT, command.type());
        assertEquals("帮我看看项目结构", command.arguments());
    }

    @Test
    void slashCommandsParseArguments() {
        TuiCommand command = TuiCommandParser.parse("/team 优化 tui");

        assertEquals(TuiCommand.Type.TEAM, command.type());
        assertEquals("优化 tui", command.arguments());
    }

    @Test
    void mcpCommandParsesSubcommandsAsArguments() {
        TuiCommand command = TuiCommandParser.parse("/mcp restart everything");

        assertEquals(TuiCommand.Type.MCP, command.type());
        assertEquals("restart everything", command.arguments());
    }

    @Test
    void skillsCommandParsesArguments() {
        TuiCommand command = TuiCommandParser.parse("/skills show core/agent");

        assertEquals(TuiCommand.Type.SKILLS, command.type());
        assertEquals("show core/agent", command.arguments());
    }

    @Test
    void renamedClearAndExitCommandsRequireSlash() {
        assertEquals(TuiCommand.Type.CLEAR, TuiCommandParser.parse("/clear").type());
        assertEquals(TuiCommand.Type.EXIT, TuiCommandParser.parse("/exit").type());
        assertEquals(TuiCommand.Type.USER_INPUT, TuiCommandParser.parse("clear").type());
        assertEquals(TuiCommand.Type.USER_INPUT, TuiCommandParser.parse("exit").type());
    }

    @Test
    void unknownSlashInputIsReservedForCommands() {
        TuiCommand command = TuiCommandParser.parse("/unknown value");

        assertEquals(TuiCommand.Type.UNKNOWN, command.type());
        assertEquals("value", command.arguments());
    }

    @Test
    void llmCommandsRequireSlashPrefix() {
        TuiCommand bareConnect = TuiCommandParser.parse("connect https://api.example.com/v1");
        assertEquals(TuiCommand.Type.USER_INPUT, bareConnect.type());
        assertEquals("connect https://api.example.com/v1", bareConnect.arguments());

        assertEquals(TuiCommand.Type.USER_INPUT, TuiCommandParser.parse("model").type());
        assertEquals(TuiCommand.Type.CONNECT, TuiCommandParser.parse("/connect https://api.example.com/v1").type());
        assertEquals(TuiCommand.Type.MODEL, TuiCommandParser.parse("/model").type());
    }

    @Test
    void productCommandsParseArguments() {
        assertEquals(TuiCommand.Type.HELP, TuiCommandParser.parse("/help tools").type());
        assertEquals("tools", TuiCommandParser.parse("/help tools").arguments());
        assertEquals(TuiCommand.Type.HELP, TuiCommandParser.parse("/?").type());
        assertEquals(TuiCommand.Type.STATUS, TuiCommandParser.parse("/status").type());
        assertEquals(TuiCommand.Type.TOOLS, TuiCommandParser.parse("/tools write").type());
        assertEquals("write", TuiCommandParser.parse("/tools write").arguments());
        assertEquals(TuiCommand.Type.LOGS, TuiCommandParser.parse("/logs tools 5").type());
        assertEquals(TuiCommand.Type.THEME, TuiCommandParser.parse("/theme compact").type());
        assertEquals(TuiCommand.Type.COMPACT, TuiCommandParser.parse("/compact").type());
    }
}
