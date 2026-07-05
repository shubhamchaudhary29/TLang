package TLang.ast;

import TLang.lexer.Token;

/**
 * AST node for a field assignment expression: set object.name to value.
 */
public final class FieldSetExpr extends Expr {

    private final Expr object;
    private final Token name;
    private final Expr value;

    public FieldSetExpr(Expr object, Token name, Expr value) {
        this.object = object;
        this.name = name;
        this.value = value;
    }

    public Expr getObject() {
        return object;
    }

    public Token getName() {
        return name;
    }

    public Expr getValue() {
        return value;
    }

    @Override
    public <R> R accept(Visitor<R> visitor) {
        return visitor.visitFieldSetExpr(this);
    }
}
