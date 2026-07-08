package dev.tlang.ast;

import dev.tlang.lexer.Token;

/**
 * Variable declaration statement: let name = initializer;
 */
public final class VarStmt extends Stmt {

    private final Token name;
    private final Expr initializer;

    public VarStmt(Token name, Expr initializer) {
        this.name = name;
        this.initializer = initializer;
    }

    public Token getName()        { return name; }
    public Expr getInitializer()  { return initializer; }

    @Override
    public void accept(Visitor visitor) {
        visitor.visitVarStmt(this);
    }
}
