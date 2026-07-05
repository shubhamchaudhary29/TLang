package TLang.ast;

/**
 * Base class for all expression AST nodes.
 *
 * Uses the Visitor pattern so the interpreter (and any future
 * passes) can process the tree without instanceof checks.
 */
public abstract class Expr {

    /** Accept a visitor and return a result of type R. */
    public abstract <R> R accept(Visitor<R> visitor);

    /** Visitor interface for all expression types. */
    public interface Visitor<R> {
        R visitBinaryExpr(BinaryExpr expr);
        R visitLogicalExpr(LogicalExpr expr);
        R visitUnaryExpr(UnaryExpr expr);
        R visitLiteralExpr(LiteralExpr expr);
        R visitGroupingExpr(GroupingExpr expr);
        R visitVariableExpr(VariableExpr expr);
        R visitAssignExpr(AssignExpr expr);
        R visitCallExpr(CallExpr expr);
        R visitLambdaExpr(LambdaExpr expr);
        R visitListExpr(ListExpr expr);
        R visitMapExpr(MapExpr expr);
        R visitIndexExpr(IndexExpr expr);
        R visitFieldAccessExpr(FieldAccessExpr expr);
        R visitIndexSetExpr(IndexSetExpr expr);
        R visitFieldSetExpr(FieldSetExpr expr);
    }
}
