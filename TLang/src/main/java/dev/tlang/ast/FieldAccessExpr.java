package dev.tlang.ast;

import dev.tlang.lexer.Token;

/**
 * AST node for a field access expression: object.name.
 */
public final class FieldAccessExpr extends Expr {

    private final Expr object;
    private final Token name;

    public FieldAccessExpr(Expr object, Token name) {
        this.object = object;
        this.name = name;
    }

    public Expr getObject() {
        return object;
    }

    public Token getName() {
        return name;
    }

    @Override
    public <R> R accept(Visitor<R> visitor) {
        return visitor.visitFieldAccessExpr(this);
    }
}
