package TLang.ast;

/**
 * While loop statement.
 *
 * while (condition) body
 */
public final class WhileStmt extends Stmt {

    private final Expr condition;
    private final Stmt body;

    public WhileStmt(Expr condition, Stmt body) {
        this.condition = condition;
        this.body = body;
    }

    public Expr getCondition() { return condition; }
    public Stmt getBody()      { return body; }

    @Override
    public void accept(Visitor visitor) {
        visitor.visitWhileStmt(this);
    }
}
