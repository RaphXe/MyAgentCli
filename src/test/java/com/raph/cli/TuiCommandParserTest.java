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
}
