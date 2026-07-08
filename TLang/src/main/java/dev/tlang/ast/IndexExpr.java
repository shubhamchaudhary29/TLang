package dev.tlang.ast;

import dev.tlang.lexer.Token;

/**
 * AST node for an index read expression: collection[index].
 */
public final class IndexExpr extends Expr {

    private final Expr collection;
    private final Token bracket;
    private final Expr index;

    public IndexExpr(Expr collection, Token bracket, Expr index) {
        this.collection = collection;
        this.bracket = bracket;
        this.index = index;
    }

    public Expr getCollection() {
        return collection;
    }

    public Token getBracket() {
        return bracket;
    }

    public Expr getIndex() {
        return index;
    }

    @Override
    public <R> R accept(Visitor<R> visitor) {
        return visitor.visitIndexExpr(this);
    }
}
