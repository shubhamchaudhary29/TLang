package TLang.ast;

/**
 * Expression statement: an expression evaluated for its side effects.
 * Example: x = x + 1;
 */
public final class ExpressionStmt extends Stmt {

    private final Expr expression;

    public ExpressionStmt(Expr expression) {
        this.expression = expression;
    }

    public Expr getExpression() { return expression; }

    @Override
    public void accept(Visitor visitor) {
        visitor.visitExpressionStmt(this);
    }
}
