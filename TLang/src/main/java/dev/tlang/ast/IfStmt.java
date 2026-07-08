package dev.tlang.ast;

/**
 * If statement with optional else branch.
 *
 * if (condition) thenBranch
 * if (condition) thenBranch else elseBranch
 */
public final class IfStmt extends Stmt {

    private final Expr condition;
    private final Stmt thenBranch;
    private final Stmt elseBranch;   // may be null

    public IfStmt(Expr condition, Stmt thenBranch, Stmt elseBranch) {
        this.condition = condition;
        this.thenBranch = thenBranch;
        this.elseBranch = elseBranch;
    }

    public Expr getCondition()   { return condition; }
    public Stmt getThenBranch()  { return thenBranch; }
    public Stmt getElseBranch()  { return elseBranch; }

    @Override
    public void accept(Visitor visitor) {
        visitor.visitIfStmt(this);
    }
}
