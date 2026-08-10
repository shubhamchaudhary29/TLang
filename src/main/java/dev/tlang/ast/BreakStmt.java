package dev.tlang.ast;

import dev.tlang.lexer.Token;

/**
 * Break statement: break
 */
public final class BreakStmt extends Stmt {

    private final Token keyword;

    public BreakStmt(Token keyword) {
        this.keyword = keyword;
    }

    public Token getKeyword() { return keyword; }

    @Override
    public void accept(Visitor visitor) {
        visitor.visitBreakStmt(this);
    }
}
