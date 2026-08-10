package dev.tlang.errors;

import dev.tlang.lexer.Token;

/** Thrown when the parser encounters a syntax error. */
public class ParseError extends RuntimeException {
    private final Token token;
    private final String message;

    public ParseError(Token token, String message) {
        super(message);
        this.token = token;
        this.message = message;
    }

    public Token getToken() { return token; }
    public String getRawMessage() { return message; }
}
