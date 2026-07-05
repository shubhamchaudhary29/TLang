package TLang.lexer;

/**
 * All token types recognized by the TLang lexer.
 */
public enum TokenType {
    // Structure tokens (indentation-based blocks)
    NEWLINE, INDENT, DEDENT,

    // Grouping & punctuation
    LEFT_PAREN, RIGHT_PAREN,
    LEFT_BRACKET, RIGHT_BRACKET,
    LEFT_BRACE, RIGHT_BRACE,
    COMMA, COLON, DOT,

    // Arithmetic operators
    PLUS, MINUS, STAR, SLASH, PERCENT,

    // Comparison operators
    EQUAL_EQUAL, BANG_EQUAL,
    GREATER, GREATER_EQUAL,
    LESS, LESS_EQUAL,

    // Literals
    NUMBER, STRING, IDENTIFIER,

    // Variable keywords
    LET, BE, SET, TO, IMPORT,

    // Output
    SHOW,

    // Control flow
    IF, OTHERWISE, WHILE, BREAK, CONTINUE,

    // Repeat loop
    REPEAT, TIMES, AS,

    // Functions
    DEFINE, TAKING, RETURN, FUNCTION,

    // Boolean keywords & values
    AND, OR, NOT,
    TRUE, FALSE,

    // End of file
    EOF
}
