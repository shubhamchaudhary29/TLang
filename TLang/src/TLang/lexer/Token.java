package TLang.lexer;

/**
 * Represents a single token produced by the lexer.
 * Tokens are immutable value objects.
 */
public final class Token {

    private final TokenType type;
    private final String lexeme;
    private final Object literal;
    private final int line;
    private final int column;

    public Token(TokenType type, String lexeme, Object literal, int line, int column) {
        this.type = type;
        this.lexeme = lexeme;
        this.literal = literal;
        this.line = line;
        this.column = column;
    }

    public Token(TokenType type, String lexeme, Object literal, int line) {
        this(type, lexeme, literal, line, 1);
    }

    public TokenType getType()  { return type; }
    public String getLexeme()   { return lexeme; }
    public Object getLiteral()  { return literal; }
    public int getLine()        { return line; }
    public int getColumn()      { return column; }

    @Override
    public String toString() {
        if (literal != null) {
            return type + "(" + literal + ")";
        }
        return type + "(" + lexeme + ")";
    }
}
