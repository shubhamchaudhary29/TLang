package TLang.ast;

import TLang.lexer.Token;

/**
 * Assignment expression: name = value.
 * Modeled as an expression so it can appear in for-loop update clauses.
 */
public final class AssignExpr extends Expr {

    private final Token name;
    private final Expr value;

    public AssignExpr(Token name, Expr value) {
        this.name = name;
        this.value = value;
    }

    public Token getName() { return name; }
    public Expr getValue() { return value; }

    @Override
    public <R> R accept(Visitor<R> visitor) {
        return visitor.visitAssignExpr(this);
    }
}
