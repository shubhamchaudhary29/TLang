package TLang.semantic;

public final class SemanticError {
    private final String message;
    private final int line;

    public SemanticError(String message, int line) {
        this.message = message;
        this.line = line;
    }

    public String getMessage() { return message; }
    public int getLine() { return line; }

    @Override
    public String toString() {
        return "[line " + line + "] Semantic error: " + message;
    }
}
