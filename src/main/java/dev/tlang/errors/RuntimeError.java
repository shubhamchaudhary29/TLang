package dev.tlang.errors;

import dev.tlang.lexer.Token;

import java.util.ArrayList;
import java.util.List;

/**
 * Thrown when the interpreter encounters an error at runtime.
 * Carries an immutable, source-aware TLang diagnostic. Java implementation
 * stack traces are deliberately disabled; language frames are tracked here.
 */
public class RuntimeError extends RuntimeException {

    private final Token token;
    private final RuntimeErrorKind kind;
    private final SourceLocation location;
    private final List<RuntimeStackFrame> frames;

    public RuntimeError(Token token, String message) {
        this(RuntimeErrorKind.RUNTIME_ERROR, token, message, null);
    }

    public RuntimeError(Token token, String message, Throwable cause) {
        this(RuntimeErrorKind.RUNTIME_ERROR, token, message, cause);
    }

    public RuntimeError(RuntimeErrorKind kind, Token token, String message) {
        this(kind, token, message, null);
    }

    public RuntimeError(RuntimeErrorKind kind, Token token, String message, Throwable cause) {
        this(kind, token, message, cause, SourceLocation.from(token), List.of());
    }

    protected RuntimeError(
            RuntimeErrorKind kind,
            Token token,
            String message,
            Throwable cause,
            SourceLocation location,
            List<RuntimeStackFrame> frames) {
        super(message, cause, false, false);
        this.token = token;
        this.kind = kind == null ? RuntimeErrorKind.RUNTIME_ERROR : kind;
        this.location = location == null ? SourceLocation.unknown() : location;
        this.frames = List.copyOf(frames);
    }

    public Token getToken() { return token; }
    public RuntimeErrorKind getKind() { return kind; }
    public SourceLocation getLocation() { return location; }
    public List<RuntimeStackFrame> getFrames() { return frames; }

    /** Return a new error with one outward TLang frame appended. */
    public RuntimeError withFrame(RuntimeStackFrame frame) {
        List<RuntimeStackFrame> updated = new ArrayList<>(frames);
        updated.add(frame);
        return new RuntimeError(kind, token, getMessage(), getCause(), location, updated);
    }
}
