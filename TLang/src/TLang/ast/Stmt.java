package TLang.ast;

/**
 * Base class for all statement AST nodes.
 */
public abstract class Stmt {

    /** Accept a visitor (statements produce no value). */
    public abstract void accept(Visitor visitor);

    /** Visitor interface for all statement types. */
    public interface Visitor {
        void visitVarStmt(VarStmt stmt);
        void visitExpressionStmt(ExpressionStmt stmt);
        void visitPrintStmt(PrintStmt stmt);
        void visitBlockStmt(BlockStmt stmt);
        void visitIfStmt(IfStmt stmt);
        void visitWhileStmt(WhileStmt stmt);
        void visitForStmt(ForStmt stmt);
        void visitFunctionStmt(FunctionStmt stmt);
        void visitReturnStmt(ReturnStmt stmt);
    }
}
