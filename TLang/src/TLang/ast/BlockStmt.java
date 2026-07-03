package TLang.ast;

import java.util.List;

/**
 * Block statement: { statements... }
 * Introduces a new lexical scope.
 */
public final class BlockStmt extends Stmt {

    private final List<Stmt> statements;

    public BlockStmt(List<Stmt> statements) {
        this.statements = statements;
    }

    public List<Stmt> getStatements() { return statements; }

    @Override
    public void accept(Visitor visitor) {
        visitor.visitBlockStmt(this);
    }
}
