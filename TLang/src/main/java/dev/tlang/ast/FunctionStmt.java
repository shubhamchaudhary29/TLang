package dev.tlang.ast;

import java.util.List;
import dev.tlang.lexer.Token;

/**
 * Function definition statement:
 *   define <name> [taking <params>]
 *       <body>
 */
public final class FunctionStmt extends Stmt {

    private final Token name;
    private final List<Token> params;
    private final List<Expr> defaults;
    private final List<Stmt> body;

    public FunctionStmt(Token name, List<Token> params, List<Expr> defaults, List<Stmt> body) {
        this.name = name;
        this.params = params;
        this.defaults = defaults;
        this.body = body;
    }

    public Token getName()         { return name; }
    public List<Token> getParams() { return params; }
    public List<Expr> getDefaults() { return defaults; }
    public List<Stmt> getBody()    { return body; }

    @Override
    public void accept(Visitor visitor) {
        visitor.visitFunctionStmt(this);
    }
}
