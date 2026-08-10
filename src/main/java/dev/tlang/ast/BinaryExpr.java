package dev.tlang.ast;

import dev.tlang.lexer.Token;

/**
 * Binary expression: left operator right.
 * Covers arithmetic (+, -, *, /, %) and comparison (==, !=, >, >=, <, <=).
 */
public final class BinaryExpr extends Expr {

    private final Expr left;
    private final Token operator;
    private final Expr right;

    public BinaryExpr(Expr left, Token operator, Expr right) {
        this.left = left;
        this.operator = operator;
        this.right = right;
    }

    public Expr getLeft()      { return left; }
    public Token getOperator() { return operator; }
    public Expr getRight()     { return right; }

    @Override
    public <R> R accept(Visitor<R> visitor) {
        return visitor.visitBinaryExpr(this);
    }
}
