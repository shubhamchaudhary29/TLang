package dev.tlang.ast;

import dev.tlang.lexer.Token;

/**
 * AST node for an index assignment expression: set collection[index] to value.
 */
public final class IndexSetExpr extends Expr {

    private final Expr collection;
    private final Token bracket;
    private final Expr index;
    private final Expr value;

    public IndexSetExpr(Expr collection, Token bracket, Expr index, Expr value) {
        this.collection = collection;
        this.bracket = bracket;
        this.index = index;
        this.value = value;
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

    public Expr getValue() {
        return value;
    }

    @Override
    public <R> R accept(Visitor<R> visitor) {
        return visitor.visitIndexSetExpr(this);
    }
}
