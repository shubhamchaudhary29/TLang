package TLang.ast;

import TLang.lexer.Token;

/**
 * Logical expression: left (&&, ||) right.
 * Separated from BinaryExpr because logical operators short-circuit.
 */
public final class LogicalExpr extends Expr {

    private final Expr left;
    private final Token operator;
    private final Expr right;

    public LogicalExpr(Expr left, Token operator, Expr right) {
        this.left = left;
        this.operator = operator;
        this.right = right;
    }

    public Expr getLeft()      { return left; }
    public Token getOperator() { return operator; }
    public Expr getRight()     { return right; }

    @Override
    public <R> R accept(Visitor<R> visitor) {
        return visitor.visitLogicalExpr(this);
    }
}
