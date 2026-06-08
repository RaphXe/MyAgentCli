package com.raph.render.inline;

import java.util.ArrayDeque;
import java.util.Deque;
final class BlockRegistry {
    private final Deque<FoldableBlock> blocks = new ArrayDeque<>();

    synchronized void register(FoldableBlock block) {
        if (block == null) {
            return;
        }
        for (FoldableBlock existing : blocks) {
            existing.freeze();
        }
        blocks.addLast(block);
    }

    synchronized boolean toggleLast() {
        FoldableBlock last = blocks.peekLast();
        return last != null && last.toggle();
    }

    synchronized boolean toggleLastForRedraw() {
        FoldableBlock last = blocks.peekLast();
        return last != null && last.toggleStateForRedraw();
    }

    synchronized void clear() {
        blocks.clear();
    }

    synchronized int size() {
        return blocks.size();
    }
}
