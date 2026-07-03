package TLang.ast;

/**
 * Print statement: print(expression);
 */
public final class PrintStmt extends Stmt {

    private final Expr expression;

    public PrintStmt(Expr expression) {
        this.expression = expression;
    }

    public Expr getExpression() { return expression; }

    @Override
    public void accept(Visitor visitor) {
        visitor.visitPrintStmt(this);
    }
}
