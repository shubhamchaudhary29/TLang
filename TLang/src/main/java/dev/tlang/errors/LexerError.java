package dev.tlang.errors;

/** Thrown when the lexer encounters invalid input. */
public class LexerError extends RuntimeException {
    private final int line;
    private final int column;
    private final String message;

    public LexerError(int line, int column, String message) {
        super(message);
        this.line = line;
        this.column = column;
        this.message = message;
    }

    public int getLine() { return line; }
    public int getColumn() { return column; }
    public String getRawMessage() { return message; }
}
