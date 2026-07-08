package dev.tlang.errors;

/**
 * Thrown by the interpreter when a "break" statement is executed.
 * Unwinds the call stack to the nearest enclosing loop.
 */
public class BreakException extends RuntimeException {

    public BreakException() {
        super(null, null, true, false); // suppress stack trace for performance
    }
}
