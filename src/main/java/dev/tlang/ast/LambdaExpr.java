package dev.tlang.ast;

import java.util.List;
import dev.tlang.lexer.Token;

/**
 * Anonymous function expression:
 *   function [taking <params>]
 *       <body>
 */
public final class LambdaExpr extends Expr {

    private final List<Token> params;
    private final List<Expr> defaults;
    private final List<Stmt> body;

    public LambdaExpr(List<Token> params, List<Expr> defaults, List<Stmt> body) {
        this.params = params;
        this.defaults = defaults;
        this.body = body;
    }

    public List<Token> getParams()   { return params; }
    public List<Expr> getDefaults()  { return defaults; }
    public List<Stmt> getBody()      { return body; }

    @Override
    public <R> R accept(Visitor<R> visitor) {
        return visitor.visitLambdaExpr(this);
    }
}
