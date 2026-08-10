package dev.tlang.errors;

/**
 * Thrown by the interpreter when a "return" statement is executed.
 *
 * This is not an error — it's a control flow mechanism that
 * unwinds the call stack back to the nearest function call site.
 */
public class ReturnException extends RuntimeException {

    private final Object value;

    public ReturnException(Object value) {
        super(null, null, true, false);  // suppress stack trace for performance
        this.value = value;
    }

    public Object getValue() { return value; }
}
