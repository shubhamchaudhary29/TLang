package TLang.runtime;

import TLang.lexer.Token;

/**
 * Thrown when the interpreter encounters an error at runtime.
 * Carries the offending token for error reporting.
 */
public class RuntimeError extends RuntimeException {

    private final Token token;

    public RuntimeError(Token token, String message) {
        super(message);
        this.token = token;
    }

    public Token getToken() { return token; }
}
