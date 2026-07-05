package TLang.parser;

import java.util.ArrayList;
import java.util.List;

import TLang.ast.*;
import TLang.lexer.Lexer;
import TLang.lexer.Token;
import TLang.lexer.TokenType;

/**
 * Recursive-descent parser for the Antigravity v2 language.
 *
 * Uses NEWLINE as statement terminators and INDENT/DEDENT for blocks,
 * eliminating semicolons and curly braces entirely.
 *
 * Grammar (informal):
 *
 *   program        → (NEWLINE | statement)* EOF
 *   statement      → varDecl | assignment | showStmt | ifStmt
 *                  | whileStmt | repeatStmt | functionDecl | returnStmt
 *                  | exprStmt
 *
 *   varDecl        → "let" IDENTIFIER "be" expression NEWLINE
 *   assignment     → "set" target "to" expression NEWLINE
 *   target         → IDENTIFIER | indexExpr | fieldAccessExpr
 *   showStmt       → "show" expression NEWLINE
 *   ifStmt         → "if" expression NEWLINE block ("otherwise" NEWLINE block)?
 *   whileStmt      → "while" expression NEWLINE block
 *   repeatStmt     → "repeat" expression "times" "as" IDENTIFIER NEWLINE block
 *   functionDecl   → "define" IDENTIFIER [ "taking" params ] NEWLINE block
 *   params         → param ( "and" param )*
 *   param          → IDENTIFIER [ "be" expression ]
 *   returnStmt     → "return" expression NEWLINE
 *   exprStmt       → expression NEWLINE
 *
 *   block          → INDENT statement+ DEDENT
 *
 *   expression     → logicOr
 *   logicOr        → logicAnd ( "or" logicAnd )*
 *   logicAnd       → equality ( "and" equality )*
 *   equality       → comparison ( ("==" | "!=") comparison )*
 *   comparison     → term ( ("<" | "<=" | ">" | ">=") term )*
 *   term           → factor ( ("+" | "-") factor )*
 *   factor         → unary ( ("*" | "/" | "%") unary )*
 *   unary          → ("not" | "-") unary | call
 *   call           → primary ( "(" arguments? ")" | "[" expression "]" | "." IDENTIFIER )*
 *   primary        → NUMBER | STRING | "true" | "false"
 *                  | IDENTIFIER | "(" expression ")"
 *                  | "function" [ "taking" params ] NEWLINE block
 *                  | "[" ( expression ( "," expression )* )? "]"
 *                  | "{" ( (IDENTIFIER | STRING) ":" expression ( "," (IDENTIFIER | STRING) ":" expression )* )? "}"
 *
 *   Note: Interpolated strings like "Hello, ${name}!" are parsed as primary strings
 *         and immediately desugared at parse time into a chain of string concatenation (+) operations.
 */
public final class Parser {

    private final List<Token> tokens;
    private int current = 0;
    private boolean lastExprEndedWithBlock = false;

    public Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    // ── Public entry point ──────────────────────────────────────

    /** Parse the token list into a list of statements. */
    public List<Stmt> parse() {
        List<Stmt> statements = new ArrayList<>();
        skipNewlines();
        while (!isAtEnd()) {
            statements.add(statement());
            skipNewlines();
        }
        return statements;
    }

    // ── Statement parsing ───────────────────────────────────────

    private Stmt statement() {
        if (check(TokenType.LET))    { advance(); return varDeclaration(); }
        if (check(TokenType.SET))    { advance(); return assignment(); }
        if (check(TokenType.SHOW))   { advance(); return showStatement(); }
        if (check(TokenType.IF))     { advance(); return ifStatement(); }
        if (check(TokenType.WHILE))  { advance(); return whileStatement(); }
        if (check(TokenType.REPEAT)) { advance(); return repeatStatement(); }
        if (check(TokenType.DEFINE)) { advance(); return functionDeclaration(); }
        if (check(TokenType.RETURN)) { advance(); return returnStatement(); }
        return expressionStatement();
    }

    /** let <identifier> be <expression> NEWLINE */
    private Stmt varDeclaration() {
        Token name = consume(TokenType.IDENTIFIER, "Expected variable name after 'let'.");
        consume(TokenType.BE, "Expected 'be' after variable name. Use: let " + name.getLexeme() + " be <value>");
        Expr initializer = expression();
        consumeNewline("Expected end of line after variable declaration.");
        return new VarStmt(name, initializer);
    }

    /** set <target> to <expression> NEWLINE */
    private Stmt assignment() {
        Expr target = call();
        consume(TokenType.TO, "Expected 'to' after assignment target.");
        Expr value = expression();
        consumeNewline("Expected end of line after assignment.");

        if (target instanceof VariableExpr) {
            return new ExpressionStmt(new AssignExpr(((VariableExpr) target).getName(), value));
        } else if (target instanceof IndexExpr) {
            IndexExpr idx = (IndexExpr) target;
            return new ExpressionStmt(new IndexSetExpr(idx.getCollection(), idx.getBracket(), idx.getIndex(), value));
        } else if (target instanceof FieldAccessExpr) {
            FieldAccessExpr fa = (FieldAccessExpr) target;
            return new ExpressionStmt(new FieldSetExpr(fa.getObject(), fa.getName(), value));
        }

        throw error(peek(), "Invalid assignment target.");
    }

    /** show <expression> NEWLINE */
    private Stmt showStatement() {
        Expr value = expression();
        consumeNewline("Expected end of line after 'show' statement.");
        return new PrintStmt(value);
    }

    /** if <expression> NEWLINE block (otherwise NEWLINE block)? */
    private Stmt ifStatement() {
        Expr condition = expression();
        consumeNewline("Expected end of line after 'if' condition.");
        Stmt thenBranch = block();

        Stmt elseBranch = null;
        skipNewlines();
        if (match(TokenType.OTHERWISE)) {
            consumeNewline("Expected end of line after 'otherwise'.");
            elseBranch = block();
        }

        return new IfStmt(condition, thenBranch, elseBranch);
    }

    /** while <expression> NEWLINE block */
    private Stmt whileStatement() {
        Expr condition = expression();
        consumeNewline("Expected end of line after 'while' condition.");
        Stmt body = block();
        return new WhileStmt(condition, body);
    }

    /**
     * repeat <count> times as <identifier> NEWLINE block
     *
     * Desugars into:
     *   BlockStmt([
     *     VarStmt(i, 0),
     *     WhileStmt(i < count, BlockStmt([...body, ExpressionStmt(i = i + 1)]))
     *   ])
     */
    private Stmt repeatStatement() {
        Expr countExpr = expression();
        consume(TokenType.TIMES, "Expected 'times' after repeat count.");
        consume(TokenType.AS, "Expected 'as' after 'times'.");
        Token varName = consume(TokenType.IDENTIFIER, "Expected loop variable name after 'as'.");
        consumeNewline("Expected end of line after repeat header.");

        // Parse the body block
        List<Stmt> bodyStatements = blockStatements();

        // Build the desugared AST:
        // let <var> be 0
        Stmt init = new VarStmt(varName, new LiteralExpr(0));

        // <var> < count
        Expr condition = new BinaryExpr(
                new VariableExpr(varName),
                syntheticToken(TokenType.LESS, "<"),
                countExpr);

        // set <var> to <var> + 1
        Expr increment = new AssignExpr(varName,
                new BinaryExpr(
                        new VariableExpr(varName),
                        syntheticToken(TokenType.PLUS, "+"),
                        new LiteralExpr(1)));

        // Append the increment to the body
        List<Stmt> augmentedBody = new ArrayList<>(bodyStatements);
        augmentedBody.add(new ExpressionStmt(increment));

        Stmt whileLoop = new WhileStmt(condition, new BlockStmt(augmentedBody));

        // Wrap in a block for scoping
        List<Stmt> wrapper = new ArrayList<>();
        wrapper.add(init);
        wrapper.add(whileLoop);
        return new BlockStmt(wrapper);
    }

    /** define <name> [taking <params>] NEWLINE block */
    private Stmt functionDeclaration() {
        Token name = consume(TokenType.IDENTIFIER, "Expected function name after 'define'.");
        List<Token> params = new ArrayList<>();
        List<Expr> defaults = new ArrayList<>();

        if (match(TokenType.TAKING)) {
            boolean seenDefault = false;
            do {
                Token param = consume(TokenType.IDENTIFIER, "Expected parameter name.");
                Expr defaultValue = null;
                if (match(TokenType.BE)) {
                    seenDefault = true;
                    defaultValue = equality();
                } else if (seenDefault) {
                    throw error(param, "Required parameter cannot follow default parameter.");
                }
                params.add(param);
                defaults.add(defaultValue);
            } while (match(TokenType.AND));
        }

        consumeNewline("Expected end of line after function declaration.");
        List<Stmt> body = blockStatements();
        return new FunctionStmt(name, params, defaults, body);
    }

    /** return <expression> NEWLINE */
    private Stmt returnStatement() {
        Token keyword = previous();
        Expr value = expression();
        consumeNewline("Expected end of line after return statement.");
        return new ReturnStmt(keyword, value);
    }

    /** expression NEWLINE */
    private Stmt expressionStatement() {
        Expr expr = expression();
        consumeNewline("Expected end of line after expression.");
        return new ExpressionStmt(expr);
    }

    // ── Block parsing ───────────────────────────────────────────

    /** Parse an INDENT block DEDENT and return it as a BlockStmt. */
    private Stmt block() {
        return new BlockStmt(blockStatements());
    }

    /** Parse an INDENT block DEDENT and return the raw statement list. */
    private List<Stmt> blockStatements() {
        consume(TokenType.INDENT, "Expected an indented block.");
        List<Stmt> statements = new ArrayList<>();
        skipNewlines();
        while (!check(TokenType.DEDENT) && !isAtEnd()) {
            statements.add(statement());
            skipNewlines();
        }
        consume(TokenType.DEDENT, "Expected end of indented block.");
        return statements;
    }

    // ── Expression parsing (lowest → highest precedence) ────────

    private Expr expression() {
        return logicOr();
    }

    private Expr logicOr() {
        Expr expr = logicAnd();
        while (match(TokenType.OR)) {
            Token operator = previous();
            Expr right = logicAnd();
            expr = new LogicalExpr(expr, operator, right);
        }
        return expr;
    }

    private Expr logicAnd() {
        Expr expr = equality();
        while (match(TokenType.AND)) {
            Token operator = previous();
            Expr right = equality();
            expr = new LogicalExpr(expr, operator, right);
        }
        return expr;
    }

    private Expr equality() {
        Expr expr = comparison();
        while (match(TokenType.EQUAL_EQUAL, TokenType.BANG_EQUAL)) {
            Token operator = previous();
            Expr right = comparison();
            expr = new BinaryExpr(expr, operator, right);
        }
        return expr;
    }

    private Expr comparison() {
        Expr expr = term();
        while (match(TokenType.GREATER, TokenType.GREATER_EQUAL,
                     TokenType.LESS, TokenType.LESS_EQUAL)) {
            Token operator = previous();
            Expr right = term();
            expr = new BinaryExpr(expr, operator, right);
        }
        return expr;
    }

    private Expr term() {
        Expr expr = factor();
        while (match(TokenType.PLUS, TokenType.MINUS)) {
            Token operator = previous();
            Expr right = factor();
            expr = new BinaryExpr(expr, operator, right);
        }
        return expr;
    }

    private Expr factor() {
        Expr expr = unary();
        while (match(TokenType.STAR, TokenType.SLASH, TokenType.PERCENT)) {
            Token operator = previous();
            Expr right = unary();
            expr = new BinaryExpr(expr, operator, right);
        }
        return expr;
    }

    private Expr unary() {
        if (match(TokenType.NOT, TokenType.MINUS)) {
            Token operator = previous();
            Expr operand = unary();
            return new UnaryExpr(operator, operand);
        }
        return call();
    }

    /** Handle function calls: primary ( "(" arguments? ")" )* */
    private Expr call() {
        Expr expr = primary();

        while (true) {
            if (match(TokenType.LEFT_PAREN)) {
                Token paren = previous();
                List<Expr> arguments = new ArrayList<>();

                if (!check(TokenType.RIGHT_PAREN)) {
                    arguments.add(expression());
                    while (match(TokenType.COMMA)) {
                        arguments.add(expression());
                    }
                }

                consume(TokenType.RIGHT_PAREN, "Expected ')' after function arguments.");
                expr = new CallExpr(expr, paren, arguments);
                lastExprEndedWithBlock = false;
            } else if (match(TokenType.LEFT_BRACKET)) {
                Token bracket = previous();
                Expr index = expression();
                consume(TokenType.RIGHT_BRACKET, "Expected ']' after index.");
                expr = new IndexExpr(expr, bracket, index);
                lastExprEndedWithBlock = false;
            } else if (match(TokenType.DOT)) {
                // Accept identifiers and keywords as property/method names after '.'
                // because built-in method names (set, get, remove, etc.) are also keywords.
                Token name;
                if (check(TokenType.IDENTIFIER) || isKeyword(peek().getType())) {
                    name = advance();
                } else {
                    throw error(peek(), "Expected property name after '.'.");
                }
                expr = new FieldAccessExpr(expr, name);
                lastExprEndedWithBlock = false;
            } else {
                break;
            }
        }

        return expr;
    }

    private Expr primary() {
        if (match(TokenType.NUMBER)) {
            lastExprEndedWithBlock = false;
            return new LiteralExpr(previous().getLiteral());
        }
        if (match(TokenType.STRING)) {
            lastExprEndedWithBlock = false;
            Token token = previous();
            String value = (String) token.getLiteral();
            if (value.contains("${")) {
                return parseInterpolatedString(value, token);
            }
            return new LiteralExpr(value);
        }
        if (match(TokenType.TRUE)) {
            lastExprEndedWithBlock = false;
            return new LiteralExpr(true);
        }
        if (match(TokenType.FALSE)) {
            lastExprEndedWithBlock = false;
            return new LiteralExpr(false);
        }

        if (match(TokenType.IDENTIFIER)) {
            lastExprEndedWithBlock = false;
            return new VariableExpr(previous());
        }

        if (match(TokenType.LEFT_PAREN)) {
            lastExprEndedWithBlock = false;
            Expr expr = expression();
            consume(TokenType.RIGHT_PAREN, "Expected ')' after expression.");
            return new GroupingExpr(expr);
        }

        if (match(TokenType.FUNCTION)) {
            List<Token> params = new ArrayList<>();
            List<Expr> defaults = new ArrayList<>();

            if (match(TokenType.TAKING)) {
                boolean seenDefault = false;
                do {
                    Token param = consume(TokenType.IDENTIFIER, "Expected parameter name.");
                    Expr defaultValue = null;
                    if (match(TokenType.BE)) {
                        seenDefault = true;
                        defaultValue = equality();
                    } else if (seenDefault) {
                        throw error(param, "Required parameter cannot follow default parameter.");
                    }
                    params.add(param);
                    defaults.add(defaultValue);
                } while (match(TokenType.AND));
            }

            consumeNewline("Expected end of line after lambda declaration.");
            List<Stmt> body = blockStatements();
            lastExprEndedWithBlock = true;
            return new LambdaExpr(params, defaults, body);
        }

        if (match(TokenType.LEFT_BRACKET)) {
            lastExprEndedWithBlock = false;
            List<Expr> elements = new ArrayList<>();
            if (!check(TokenType.RIGHT_BRACKET)) {
                elements.add(expression());
                while (match(TokenType.COMMA)) {
                    elements.add(expression());
                }
            }
            consume(TokenType.RIGHT_BRACKET, "Expected ']' after list literal.");
            return new ListExpr(elements);
        }

        if (match(TokenType.LEFT_BRACE)) {
            lastExprEndedWithBlock = false;
            List<String> keys = new ArrayList<>();
            List<Expr> values = new ArrayList<>();
            if (!check(TokenType.RIGHT_BRACE)) {
                do {
                    Token keyToken;
                    if (match(TokenType.IDENTIFIER, TokenType.STRING)) {
                        keyToken = previous();
                    } else {
                        throw error(peek(), "Expected map key (identifier or string).");
                    }
                    String key = (keyToken.getType() == TokenType.STRING)
                        ? (String) keyToken.getLiteral()
                        : keyToken.getLexeme();

                    consume(TokenType.COLON, "Expected ':' after map key.");
                    Expr value = expression();

                    keys.add(key);
                    values.add(value);
                } while (match(TokenType.COMMA));
            }
            consume(TokenType.RIGHT_BRACE, "Expected '}' after map literal.");
            return new MapExpr(keys, values);
        }

        throw error(peek(), "Expected expression.");
    }

    // ── Token helpers ───────────────────────────────────────────

    private boolean match(TokenType... types) {
        for (TokenType type : types) {
            if (check(type)) {
                advance();
                return true;
            }
        }
        return false;
    }

    private boolean check(TokenType type) {
        return !isAtEnd() && peek().getType() == type;
    }

    private Token advance() {
        if (!isAtEnd()) current++;
        return previous();
    }

    private Token peek() {
        return tokens.get(current);
    }

    private Token previous() {
        return tokens.get(current - 1);
    }

    private boolean isAtEnd() {
        return peek().getType() == TokenType.EOF;
    }

    /** Returns true if the given token type is a keyword (as opposed to a symbol or literal). */
    private boolean isKeyword(TokenType type) {
        switch (type) {
            case LET: case BE: case SET: case TO:
            case SHOW: case IF: case OTHERWISE: case WHILE:
            case REPEAT: case TIMES: case AS:
            case DEFINE: case TAKING: case RETURN: case FUNCTION:
            case AND: case OR: case NOT:
            case TRUE: case FALSE:
                return true;
            default:
                return false;
        }
    }

    private Token consume(TokenType type, String message) {
        if (check(type)) return advance();
        throw error(peek(), message);
    }

    /** Consume a NEWLINE or EOF (end of statement). */
    private void consumeNewline(String message) {
        if (check(TokenType.NEWLINE) || check(TokenType.EOF)) {
            if (check(TokenType.NEWLINE)) advance();
            lastExprEndedWithBlock = false;
            return;
        }
        if (lastExprEndedWithBlock) {
            lastExprEndedWithBlock = false;
            return;
        }
        throw error(peek(), message);
    }

    /** Skip any number of consecutive NEWLINE tokens. */
    private void skipNewlines() {
        while (check(TokenType.NEWLINE)) {
            advance();
        }
    }

    /** Create a synthetic token for desugared AST construction. */
    private Token syntheticToken(TokenType type, String lexeme) {
        return new Token(type, lexeme, null, peek().getLine());
    }

    /** Expose a method to parse a single expression only, ensuring all tokens are consumed. */
    public Expr parseExpressionOnly() {
        skipNewlines();
        Expr expr = expression();
        skipNewlines();
        if (!isAtEnd()) {
            throw error(peek(), "Malformed interpolation expression.");
        }
        return expr;
    }

    private Expr parseSubExpression(String source, Token outerToken) {
        try {
            Lexer lexer = new Lexer(source);
            List<Token> subTokens = lexer.tokenize();
            Parser parser = new Parser(subTokens);
            return parser.parseExpressionOnly();
        } catch (Parser.ParseError | Lexer.LexerError e) {
            throw new ParseError("[line " + outerToken.getLine() + "] Parse error: Malformed interpolation expression.");
        }
    }

    private Expr parseInterpolatedString(String value, Token outerToken) {
        List<Expr> parts = new ArrayList<>();
        int i = 0;
        int len = value.length();

        while (i < len) {
            int startInterpolation = value.indexOf("${", i);
            if (startInterpolation == -1) {
                parts.add(new LiteralExpr(value.substring(i)));
                break;
            }

            if (startInterpolation > i) {
                parts.add(new LiteralExpr(value.substring(i, startInterpolation)));
            }

            int exprStart = startInterpolation + 2;
            int braceDepth = 1;
            int j = exprStart;
            while (j < len && braceDepth > 0) {
                char c = value.charAt(j);
                if (c == '{') braceDepth++;
                else if (c == '}') braceDepth--;
                j++;
            }

            if (braceDepth > 0) {
                throw new ParseError("[line " + outerToken.getLine() + "] Parse error: Malformed string interpolation.");
            }

            String exprSource = value.substring(exprStart, j - 1);
            i = j;

            Expr parsedExpr = parseSubExpression(exprSource, outerToken);
            parts.add(parsedExpr);
        }

        Expr result = null;
        for (Expr part : parts) {
            if (part instanceof LiteralExpr) {
                Object val = ((LiteralExpr) part).getValue();
                if (val instanceof String && ((String) val).isEmpty()) {
                    continue;
                }
            }

            if (result == null) {
                result = part;
            } else {
                Token plus = new Token(TokenType.PLUS, "+", null, outerToken.getLine());
                result = new BinaryExpr(result, plus, part);
            }
        }

        if (result == null) {
            return new LiteralExpr("");
        }
        return result;
    }

    // ── Error reporting ─────────────────────────────────────────

    private ParseError error(Token token, String message) {
        String location = token.getType() == TokenType.EOF
                ? "at end of input"
                : "at '" + token.getLexeme() + "'";
        if (token.getType() == TokenType.NEWLINE) location = "at end of line";
        if (token.getType() == TokenType.INDENT)  location = "at start of block";
        if (token.getType() == TokenType.DEDENT)  location = "at end of block";
        return new ParseError("[line " + token.getLine() + "] Parse error "
                + location + ": " + message);
    }

    /** Thrown when the parser encounters a syntax error. */
    public static class ParseError extends RuntimeException {
        public ParseError(String message) {
            super(message);
        }
    }
}
