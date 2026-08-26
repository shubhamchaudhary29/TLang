package dev.tlang.ast;

import dev.tlang.lexer.Token;

/** A blocking wait for an opaque TLang task value. */
public final class AwaitExpr extends Expr {
    private final Token keyword;
    private final Expr task;

    public AwaitExpr(Token keyword, Expr task) {
        this.keyword = keyword;
        this.task = task;
    }

    public Token getKeyword() { return keyword; }
    public Expr getTask() { return task; }

    @Override
    public <R> R accept(Visitor<R> visitor) {
        return visitor.visitAwaitExpr(this);
    }
}
