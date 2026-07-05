package TLang.runtime;

/**
 * Thrown by the interpreter when a "continue" statement is executed.
 * Unwinds the call stack to the end of the current loop iteration.
 */
public class ContinueException extends RuntimeException {

    public ContinueException() {
        super(null, null, true, false); // suppress stack trace for performance
    }
}
