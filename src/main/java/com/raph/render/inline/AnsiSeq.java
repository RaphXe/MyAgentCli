package com.raph.render.inline;

final class AnsiSeq {
    static final String ESC = "\u001B";
    static final String CLEAR_TO_EOS = ESC + "[J";
    static final String CLEAR_TO_EOL = ESC + "[K";
    static final String RESET = ESC + "[0m";
    static final String BOLD_DARK_BLUE = ESC + "[1;34m";

    private AnsiSeq() {
    }

    static String moveUp(int rows) {
        return ESC + "[" + Math.max(1, rows) + "A";
    }
}
