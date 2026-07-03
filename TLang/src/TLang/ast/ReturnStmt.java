package TLang.ast;

import TLang.lexer.Token;

/**
 * Return statement: return <expression>
 */
public final class ReturnStmt extends Stmt {

    private final Token keyword;
    private final Expr value;

    public ReturnStmt(Token keyword, Expr value) {
        this.keyword = keyword;
        this.value = value;
    }

    public Token getKeyword() { return keyword; }
    public Expr getValue()    { return value; }

    @Override
    public void accept(Visitor visitor) {
        visitor.visitReturnStmt(this);
    }
}
