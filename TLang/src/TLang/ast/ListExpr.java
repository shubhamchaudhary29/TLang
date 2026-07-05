package TLang.ast;

import java.util.List;

/**
 * AST node for a list literal: [expr1, expr2, ...].
 */
public final class ListExpr extends Expr {

    private final List<Expr> elements;

    public ListExpr(List<Expr> elements) {
        this.elements = elements;
    }

    public List<Expr> getElements() {
        return elements;
    }

    @Override
    public <R> R accept(Visitor<R> visitor) {
        return visitor.visitListExpr(this);
    }
}
