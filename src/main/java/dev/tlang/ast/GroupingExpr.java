package dev.tlang.ast;

/**
 * Parenthesized (grouping) expression: ( expression ).
 */
public final class GroupingExpr extends Expr {

    private final Expr expression;

    public GroupingExpr(Expr expression) {
        this.expression = expression;
    }

    public Expr getExpression() { return expression; }

    @Override
    public <R> R accept(Visitor<R> visitor) {
        return visitor.visitGroupingExpr(this);
    }
}
