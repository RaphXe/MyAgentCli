package com.raph.interaction;

/**
 * Normalized terminal input failure.
 */
public class InteractionException extends Exception {
    public enum Type {
        INTERRUPTED,
        EOF,
        IO
    }

    private final Type type;

    public InteractionException(Type type, String message) {
        super(message);
        this.type = type == null ? Type.IO : type;
    }

    public InteractionException(Type type, String message, Throwable cause) {
        super(message, cause);
        this.type = type == null ? Type.IO : type;
    }

    public Type type() {
        return type;
    }
}
