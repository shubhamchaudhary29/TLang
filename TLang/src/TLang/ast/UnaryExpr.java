package TLang.ast;

import TLang.lexer.Token;

/**
 * Unary expression: operator operand.
 * Supports logical NOT (!) and arithmetic negation (-).
 */
public final class UnaryExpr extends Expr {

    private final Token operator;
    private final Expr operand;

    public UnaryExpr(Token operator, Expr operand) {
        this.operator = operator;
        this.operand = operand;
    }

    public Token getOperator() { return operator; }
    public Expr getOperand()   { return operand; }

    @Override
    public <R> R accept(Visitor<R> visitor) {
        return visitor.visitUnaryExpr(this);
    }
}
