package dev.tlang.ast;

import dev.tlang.lexer.Token;

/**
 * Variable reference expression: reading a variable by name.
 */
public final class VariableExpr extends Expr {

    private final Token name;

    public VariableExpr(Token name) {
        this.name = name;
    }

    public Token getName() { return name; }

    @Override
    public <R> R accept(Visitor<R> visitor) {
        return visitor.visitVariableExpr(this);
    }
}
