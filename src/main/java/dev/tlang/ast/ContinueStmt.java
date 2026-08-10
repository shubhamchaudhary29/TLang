package dev.tlang.ast;

import dev.tlang.lexer.Token;

/**
 * Continue statement: continue
 */
public final class ContinueStmt extends Stmt {

    private final Token keyword;

    public ContinueStmt(Token keyword) {
        this.keyword = keyword;
    }

    public Token getKeyword() { return keyword; }

    @Override
    public void accept(Visitor visitor) {
        visitor.visitContinueStmt(this);
    }
}
