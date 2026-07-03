package TLang.ast;

/**
 * C-style for loop statement.
 *
 * for (initializer; condition; update) body
 *
 * The initializer is a statement (VarStmt or ExpressionStmt).
 * The condition and update are expressions.
 */
public final class ForStmt extends Stmt {

    private final Stmt initializer;   // VarStmt or ExpressionStmt
    private final Expr condition;
    private final Expr update;
    private final Stmt body;

    public ForStmt(Stmt initializer, Expr condition, Expr update, Stmt body) {
        this.initializer = initializer;
        this.condition = condition;
        this.update = update;
        this.body = body;
    }

    public Stmt getInitializer() { return initializer; }
    public Expr getCondition()   { return condition; }
    public Expr getUpdate()      { return update; }
    public Stmt getBody()        { return body; }

    @Override
    public void accept(Visitor visitor) {
        visitor.visitForStmt(this);
    }
}
