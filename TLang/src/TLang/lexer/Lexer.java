package TLang.lexer;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Indentation-aware lexer for the Antigravity v2 language.
 *
 * Produces NEWLINE, INDENT, and DEDENT tokens (Python-style)
 * instead of relying on semicolons and curly braces.
 *
 * Handles:
 *   - Indentation tracking via a stack
 *   - Single-line comments (//)
 *   - Keywords, identifiers, integer literals, string literals
 *   - All operators and punctuation
 */
public final class Lexer {

    private final String source;
    private final List<Token> tokens = new ArrayList<>();
    private final Deque<Integer> indentStack = new ArrayDeque<>();

    private int start   = 0;
    private int current = 0;
    private int line    = 1;
    private boolean atLineStart = true;

    private static final Map<String, TokenType> KEYWORDS = new HashMap<>();

    static {
        // Variable keywords
        KEYWORDS.put("let",       TokenType.LET);
        KEYWORDS.put("be",        TokenType.BE);
        KEYWORDS.put("set",       TokenType.SET);
        KEYWORDS.put("to",        TokenType.TO);

        // Output
        KEYWORDS.put("show",      TokenType.SHOW);

        // Control flow
        KEYWORDS.put("if",        TokenType.IF);
        KEYWORDS.put("otherwise", TokenType.OTHERWISE);
        KEYWORDS.put("while",     TokenType.WHILE);

        // Repeat loop
        KEYWORDS.put("repeat",    TokenType.REPEAT);
        KEYWORDS.put("times",     TokenType.TIMES);
        KEYWORDS.put("as",        TokenType.AS);

        // Functions
        KEYWORDS.put("define",    TokenType.DEFINE);
        KEYWORDS.put("taking",    TokenType.TAKING);
        KEYWORDS.put("return",    TokenType.RETURN);

        // Boolean keywords
        KEYWORDS.put("and",       TokenType.AND);
        KEYWORDS.put("or",        TokenType.OR);
        KEYWORDS.put("not",       TokenType.NOT);

        // Boolean literals
        KEYWORDS.put("true",      TokenType.TRUE);
        KEYWORDS.put("false",     TokenType.FALSE);
    }

    public Lexer(String source) {
        this.source = source;
        this.indentStack.push(0);
    }

    // ── Public entry point ──────────────────────────────────────

    /** Tokenize the entire source and return the token list. */
    public List<Token> tokenize() {
        while (!isAtEnd()) {
            if (atLineStart) {
                processLineStart();
            } else {
                start = current;
                scanToken();
            }
        }

        // Emit a final NEWLINE if the last line didn't end with one
        if (!tokens.isEmpty() && lastTokenType() != TokenType.NEWLINE) {
            addSimple(TokenType.NEWLINE);
        }

        // Close all remaining indentation levels
        while (indentStack.size() > 1) {
            addSimple(TokenType.DEDENT);
            indentStack.pop();
        }

        addSimple(TokenType.EOF);
        return tokens;
    }

    // ── Indentation handling ────────────────────────────────────

    /**
     * Called at the start of every logical line.
     * Counts leading spaces, skips blank/comment-only lines,
     * and emits INDENT/DEDENT tokens as needed.
     */
    private void processLineStart() {
        int spaces = 0;
        while (!isAtEnd() && (peek() == ' ' || peek() == '\t')) {
            if (peek() == '\t') {
                spaces += 4;  // treat tab as 4 spaces
            } else {
                spaces++;
            }
            current++;
        }

        // Skip blank lines entirely
        if (isAtEnd() || peek() == '\n') {
            if (!isAtEnd()) {
                current++;  // skip the \n
                line++;
            }
            return;  // stay in atLineStart mode
        }

        // Skip comment-only lines
        if (peek() == '/' && peekNext() == '/') {
            skipToEndOfLine();
            return;  // stay in atLineStart mode
        }

        // Compare with current indentation level
        int currentIndent = indentStack.peek();

        if (spaces > currentIndent) {
            indentStack.push(spaces);
            addSimple(TokenType.INDENT);
        } else if (spaces < currentIndent) {
            while (indentStack.peek() > spaces) {
                indentStack.pop();
                addSimple(TokenType.DEDENT);
            }
            if (indentStack.peek() != spaces) {
                throw error("Inconsistent indentation. Expected "
                        + indentStack.peek() + " spaces but found " + spaces + ".");
            }
        }

        atLineStart = false;
    }

    // ── Token scanning ──────────────────────────────────────────

    private void scanToken() {
        char c = advance();
        switch (c) {
            // Newline → emit NEWLINE and switch to line-start mode
            case '\n':
                // Don't emit duplicate NEWLINEs
                if (lastTokenType() != TokenType.NEWLINE) {
                    addSimple(TokenType.NEWLINE);
                }
                line++;
                atLineStart = true;
                break;

            // Skip inline whitespace
            case ' ': case '\r': case '\t':
                break;

            // Grouping & punctuation
            case '(': addToken(TokenType.LEFT_PAREN);  break;
            case ')': addToken(TokenType.RIGHT_PAREN); break;
            case ',': addToken(TokenType.COMMA);        break;

            // Arithmetic operators
            case '+': addToken(TokenType.PLUS);    break;
            case '-': addToken(TokenType.MINUS);   break;
            case '*': addToken(TokenType.STAR);    break;
            case '%': addToken(TokenType.PERCENT); break;

            case '/':
                if (match('/')) {
                    // Single-line comment: skip to end of line
                    while (!isAtEnd() && peek() != '\n') advance();
                } else {
                    addToken(TokenType.SLASH);
                }
                break;

            // Comparison & equality operators
            case '=':
                if (match('=')) {
                    addToken(TokenType.EQUAL_EQUAL);
                } else {
                    throw error("Unexpected '='. Use 'let x be ...' or 'set x to ...' for assignment.");
                }
                break;

            case '!':
                if (match('=')) {
                    addToken(TokenType.BANG_EQUAL);
                } else {
                    throw error("Unexpected '!'. Use 'not' for boolean negation.");
                }
                break;

            case '>': addToken(match('=') ? TokenType.GREATER_EQUAL : TokenType.GREATER); break;
            case '<': addToken(match('=') ? TokenType.LESS_EQUAL    : TokenType.LESS);    break;

            // String literals
            case '"': string(); break;

            default:
                if (isDigit(c)) {
                    number();
                } else if (isAlpha(c)) {
                    identifier();
                } else {
                    throw error("Unexpected character '" + c + "'.");
                }
                break;
        }
    }

    // ── Literal scanning ────────────────────────────────────────

    private void number() {
        while (isDigit(peek())) advance();
        String text = source.substring(start, current);
        addToken(TokenType.NUMBER, Integer.parseInt(text));
    }

    private void string() {
        int startLine = line;
        while (!isAtEnd() && peek() != '"') {
            if (peek() == '\n') line++;
            advance();
        }
        if (isAtEnd()) {
            throw new LexerError("[line " + startLine + "] Lexer error: Unterminated string.");
        }
        advance();  // closing "

        // Extract string value without quotes
        String value = source.substring(start + 1, current - 1);
        addToken(TokenType.STRING, value);
    }

    private void identifier() {
        while (isAlphaNumeric(peek())) advance();
        String text = source.substring(start, current);
        TokenType type = KEYWORDS.getOrDefault(text, TokenType.IDENTIFIER);
        addToken(type);
    }

    // ── Character helpers ───────────────────────────────────────

    private char advance() {
        return source.charAt(current++);
    }

    private char peek() {
        return isAtEnd() ? '\0' : source.charAt(current);
    }

    private char peekNext() {
        return (current + 1 >= source.length()) ? '\0' : source.charAt(current + 1);
    }

    private boolean match(char expected) {
        if (isAtEnd() || source.charAt(current) != expected) return false;
        current++;
        return true;
    }

    private boolean isAtEnd() {
        return current >= source.length();
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isAlpha(char c) {
        return (c >= 'a' && c <= 'z')
            || (c >= 'A' && c <= 'Z')
            || c == '_';
    }

    private static boolean isAlphaNumeric(char c) {
        return isAlpha(c) || isDigit(c);
    }

    private void skipToEndOfLine() {
        while (!isAtEnd() && peek() != '\n') current++;
        if (!isAtEnd()) {
            current++;  // skip the \n
            line++;
        }
        // stay in atLineStart mode
    }

    // ── Token helpers ───────────────────────────────────────────

    private void addToken(TokenType type) {
        addToken(type, null);
    }

    private void addToken(TokenType type, Object literal) {
        String text = source.substring(start, current);
        tokens.add(new Token(type, text, literal, line));
    }

    /** Add a synthetic token with no source text (NEWLINE, INDENT, DEDENT, EOF). */
    private void addSimple(TokenType type) {
        tokens.add(new Token(type, "", null, line));
    }

    private TokenType lastTokenType() {
        if (tokens.isEmpty()) return null;
        return tokens.get(tokens.size() - 1).getType();
    }

    // ── Error reporting ─────────────────────────────────────────

    private LexerError error(String message) {
        return new LexerError("[line " + line + "] Lexer error: " + message);
    }

    /** Thrown when the lexer encounters invalid input. */
    public static class LexerError extends RuntimeException {
        public LexerError(String message) {
            super(message);
        }
    }
}
