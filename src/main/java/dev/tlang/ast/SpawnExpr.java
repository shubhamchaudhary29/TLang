package dev.tlang.ast;

import dev.tlang.lexer.Token;

/** A background task created from one already-parsed function call. */
public final class SpawnExpr extends Expr {
    private final Token keyword;
    private final CallExpr call;

    public SpawnExpr(Token keyword, CallExpr call) {
        this.keyword = keyword;
        this.call = call;
    }

    public Token getKeyword() { return keyword; }
    public CallExpr getCall() { return call; }

    @Override
    public <R> R accept(Visitor<R> visitor) {
        return visitor.visitSpawnExpr(this);
    }
}
